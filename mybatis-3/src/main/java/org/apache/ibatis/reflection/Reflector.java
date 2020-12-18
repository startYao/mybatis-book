/**
 * Copyright 2009-2018 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * 反射器
 * 对个Reflector对应一个类，Reflector会缓存反射操作需要的类的信息，例如：构造方法，属性名，setting/getting方法等等。
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {
    /*对应的类*/
    private final Class<?> type;
    /*可读属性数组*/
    private final String[] readablePropertyNames;
    /*可写属性集合*/
    private final String[] writeablePropertyNames;
    /*属性对应的setting方法映射*/
    private final Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
    /*属性对应的getting方法映射*/
    private final Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
    /*属性对应的setting方法的(返回值参数)映射*/
    private final Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
    /*属性对应的getting方法的(返回值参数)映射*/
    private final Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
    /*默认构造方法*/
    private Constructor<?> defaultConstructor;
    /**
     * 不区分大小写的属性集合
     */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

    public Reflector(Class<?> clazz) {
        // 设置对应的类
        type = clazz;
        // <1> 初始化 defaultConstructor
        addDefaultConstructor(clazz);
        // <2> // 初始化 getMethods 和 getTypes ，通过遍历 getting 方法
        addGetMethods(clazz);
        // <3> // 初始化 setMethods 和 setTypes ，通过遍历 setting 方法。
        addSetMethods(clazz);
        // <4> // 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性
        addFields(clazz);
        // <5> 初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap 属性
        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writeablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    /**
     * 添加默认的构造方法
     *
     * @param clazz
     */
    private void addDefaultConstructor(Class<?> clazz) {
        // 获得所有构造方法
        Constructor<?>[] consts = clazz.getDeclaredConstructors();
        // 遍历所有构造方法，查找无参的构造方法
        for (Constructor<?> constructor : consts) {
            // 判断无参的构造方法
            if (constructor.getParameterTypes().length == 0) {
                // 设置构造方法可以访问，避免是 private 等修饰符
                if (canControlMemberAccessible()) {
                    try {
                        constructor.setAccessible(true);
                    } catch (Exception e) {
                        // Ignored. This is only a final precaution, nothing we can do.
                    }
                }
                // 如果构造方法可以访问，赋值给 defaultConstructor
                if (constructor.isAccessible()) {
                    this.defaultConstructor = constructor;
                }
            }
        }
    }

    /**
     * 存储getmethod
     *
     * @param cls
     */
    private void addGetMethods(Class<?> cls) {
        //1.属性与getting方法的映射
        Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
        //2.获取所有的方法
        Method[] methods = getClassMethods(cls);
        //3.遍历所有方法
        for (Method method : methods) {
            //3.1参数大于0，说明不是getting方法
            if (method.getParameterTypes().length > 0) {
                continue;
            }
            //3.2以get和is方法名开头，说明是getting方法
            String name = method.getName();
            if ((name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2)) {
                //3.3获取属性
                name = PropertyNamer.methodToProperty(name);
                //3.4添加到conflictingGetters中
                addMethodConflict(conflictingGetters, name, method);
            }
        }
        //4.解决getting冲突方法
        resolveGetterConflicts(conflictingGetters);
    }

  /**
   * 解决getting中冲突的方法，一个属性只能保留一个对应的方法
   * @param conflictingGetters （需要进行解决冲突的方法map）
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法
        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            Method winner = null; // 最匹配的方法
            String propName = entry.getKey();
            for (Method candidate : entry.getValue()) {
                if (winner == null) {
                    winner = candidate;
                    continue;
                }
                // <1> 基于返回类型比较
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();
                // 类型相同
                if (candidateType.equals(winnerType)) {
                    if (!boolean.class.equals(candidateType)) {
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property "
                                        + propName + " in class " + winner.getDeclaringClass()
                                        + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                    // 选择 boolean 类型的 is 方法
                    } else if (candidate.getName().startsWith("is")) {
                        winner = candidate;
                    }
                // 不符合选择子类（说明candidateType是父类，winnerType是子类）
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    // OK getter type is descendant
                // <1.1> 符合选择子类。因为子类可以修改放大返回值。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList 。
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    winner = candidate;
                // <1.2> 返回类型冲突，抛出 ReflectionException 异常
                } else {
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property "
                                    + propName + " in class " + winner.getDeclaringClass()
                                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
             // <2> 添加到 getMethods 和 getTypes 中
            addGetMethod(propName, winner);
        }
    }

    /**
     * <2.2> 处，添加到 getMethods 中。此处，我们可以看到一个 MethodInvoker 类，详细解析，见 「4.3 MethodInvoker」
     * <2.3> 处，添加到 getTypes 中。此处，我们可以看到一个 TypeParameterResolver 类，详细解析，见 「14. TypeParameterResolver」
     * @param name
     * @param method
     */
    private void addGetMethod(String name, Method method) {
        // <2.1> 判断是合理的属性名
        if (isValidPropertyName(name)) {
            // <2.2> 添加到 getMethods 中
            getMethods.put(name, new MethodInvoker(method));
            // <2.3> 添加到 getTypes 中
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            getTypes.put(name, typeToClass(returnType));
        }
    }

    /**
     * 初始化 setMethods 和 setTypes ，通过遍历 setting 方法
     * @param cls
     */
    private void addSetMethods(Class<?> cls) {
        //属性与其setting方法的映射
        Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
        //获取所有的方法
        Method[] methods = getClassMethods(cls);
        //遍历所有方法
        for (Method method : methods) {
            String name = method.getName();
            //1.方法名为set开头
            //参数数量为1
            if (name.startsWith("set") && name.length() > 3) {
                if (method.getParameterTypes().length == 1) {
                    //获取到属性
                    name = PropertyNamer.methodToProperty(name);
                    //添加到conflictingSetters 中
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }
        //解决setting方法冲突（子类集成父类，如Collection->ArrayList,然后取子类的方法）
        resolveSetterConflicts(conflictingSetters);
    }

    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        //java8的写法
        List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
        list.add(method);

        //java7写法
        //    List<Method> list = conflictingMethods.get(name);
        //    if (list == null) {
        //      list = new ArrayList<Method>();
        //      conflictingMethods.put(name, list);
        //    }
        //    list.add(method);
    }

    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        //遍历每个属性，查找其最匹配的方法，因为子类可以腹泻父类的方法，所以一个属性，可能对应多个setting方法
        for (String propName : conflictingSetters.keySet()) {
            List<Method> setters = conflictingSetters.get(propName);
            Class<?> getterType = getTypes.get(propName);
            Method match = null;
            ReflectionException exception = null;
            ///遍历属性对应的setting方法
            for (Method setter : setters) {
                Class<?> paramType = setter.getParameterTypes()[0];
                if (paramType.equals(getterType)) {
                    // should be the best match
                    match = setter;
                    break;
                }
                if (exception == null) {
                    try {
                        //悬着一个更加匹配的
                        match = pickBetterSetter(match, setter, propName);
                    } catch (ReflectionException e) {
                        // there could still be the 'best match'
                        match = null;
                        exception = e;
                    }
                }
            }
            // <2> 添加到 setMethods 和 setTypes 中
            if (match == null) {
                throw exception;
            } else {
                addSetMethod(propName, match);
            }
        }
    }

    private Method pickBetterSetter(Method setter1, Method setter2, String property) {
        if (setter1 == null) {
            return setter2;
        }
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            return setter1;
        }
        throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
                + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
                + paramType2.getName() + "'.");
    }

    private void addSetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            // 添加到 setMethods 中
            setMethods.put(name, new MethodInvoker(method));
            // 添加到 setTypes 中
            Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
            setTypes.put(name, typeToClass(paramTypes[0]));
        }
    }

    /**
     * 寻找Type真正对应的类
     * @param src
     * @return
     */
    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        //普通类型，直接使用类
        if (src instanceof Class) {
            result = (Class<?>) src;
        //泛型类型, 使用泛型
        } else if (src instanceof ParameterizedType) {
            result = (Class<?>) ((ParameterizedType) src).getRawType();
        // 泛型数组，获得具体类
        } else if (src instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) { // 普通类型
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                Class<?> componentClass = typeToClass(componentType); // 递归该方法，返回类
                result = Array.newInstance((Class<?>) componentClass, 0).getClass();
            }
        }
        // 都不符合，使用 Object 类
        if (result == null) {
            result = Object.class;
        }
        return result;
    }

    /**
     * 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性。
     * 实际上，它是 #addGetMethods(...) 和 #addSetMethods(...) 方法的补充，因为有些 field ，不存在对应的 setting 或 getting 方法，所以直接使用对应的 field
     * @param clazz
     */
    private void addFields(Class<?> clazz) {
        // 获得所有 field 们
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
             // 设置 field 可访问
            if (canControlMemberAccessible()) {
                try {
                    field.setAccessible(true);
                } catch (Exception e) {
                    // Ignored. This is only a final precaution, nothing we can do.
                }
            }
            if (field.isAccessible()) {
                //1.添加到setmethods和setType中
                if (!setMethods.containsKey(field.getName())) {
                    // issue #379 - removed the check for final because JDK 1.5 allows
                    // modification of final fields through reflection (JSR-133). (JGB)
                    // pr #16 - final static can only be set by the classloader
                    int modifiers = field.getModifiers();
                    if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                        addSetField(field);
                    }
                }
                //1.添加到getmethods和getType中
                if (!getMethods.containsKey(field.getName())) {
                    addGetField(field);
                }
            }
        }
        // 递归，处理父类
        if (clazz.getSuperclass() != null) {
            addFields(clazz.getSuperclass());
        }
    }

    private void addSetField(Field field) {
        // 判断是合理的属性
        if (isValidPropertyName(field.getName())) {
            // 添加到 setMethods 中
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            // 添加到 setTypes 中
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /*
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler Class.getMethods(),
     * because we want to look for private methods as well.
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        //每个方法签名与该方法的映射
        Map<String, Method> uniqueMethods = new HashMap<String, Method>();
        // 循环类，类的父类，类的父类的父类，直到父类为 Object
        Class<?> currentClass = cls;
        while (currentClass != null && currentClass != Object.class) {
            // <1> 记录当前类定义的方法
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract
            // <2> 记录接口中定义的方法
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }
            // 获得父类
            currentClass = currentClass.getSuperclass();
        }
        // 转换成 Method 数组返回
        Collection<Method> methods = uniqueMethods.values();
        // 返回指定类型的数组
        return methods.toArray(new Method[methods.size()]);
    }

    /**
     * 添加方法，将方法进行签名，存放到uniqueMethods中
     */
    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
            // 忽略 bridge 方法，参见 https://www.zhihu.com/question/54895701/answer/141623158 文章
            if (!currentMethod.isBridge()) {
                // <3> 获得方法签名
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                //当 uniqueMethods 不存在时，进行添加,存在了就不进行添加
                if (!uniqueMethods.containsKey(signature)) {
                    // 设置方法可访问
                    if (canControlMemberAccessible()) {
                        try {
                            currentMethod.setAccessible(true);
                        } catch (Exception e) {
                            // Ignored. This is only a final precaution, nothing we can do.
                        }
                    }
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

  /**
   * 返回格式： returnType#方法名:参数名1,参数名2,参数名3
   * 例子：    void#checkPackageAccess:java.lang.ClassLoader,boolean
   * @param method
   * @return
   */
    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        // 返回类型
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        // 方法名
        sb.append(method.getName());
        // 方法参数
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * 判断，是否可以修改可访问性
     * Checks whether can control member accessible.
     *
     * @return If can control member accessible, it return {@literal true}
     * @since 3.5.0
     */
    public static boolean canControlMemberAccessible() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /*
     * Gets the name of the class the instance provides information for
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /*
     * Gets the type for a property setter
     *
     * @param propertyName - the name of the property
     * @return The Class of the propery setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /*
     * Gets the type for a property getter
     *
     * @param propertyName - the name of the property
     * @return The Class of the propery getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /*
     * Gets an array of the readable properties for an object
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /*
     * Gets an array of the writeable properties for an object
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writeablePropertyNames;
    }

    /*
     * Check to see if a class has a writeable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writeable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /*
     * Check to see if a class has a readable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
