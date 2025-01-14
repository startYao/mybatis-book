/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection.invoker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * 实现Invoker接口，获取Field调用者
 * @author Clinton Begin
 */
public class GetFieldInvoker implements Invoker {

  /**
   * field对象
   */
  private final Field field;

  public GetFieldInvoker(Field field) {
    this.field = field;
  }

  //获取属性
  @Override
  public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
    return field.get(target);
  }

  /**
   * 返回属性类型
   * @return
   */
  @Override
  public Class<?> getType() {
    return field.getType();
  }
}
