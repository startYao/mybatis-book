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
package org.apache.ibatis.parsing;

/**
 * @author Clinton Begin
 * 就一个 #parse(String text) 方法，循环( 因为可能不只一个 )，解析以 openToken 开始，以 closeToken 结束的 Token ，并提交给 handler 进行处理，即 <x> 处。
 * 所以所以所以，胖友可以耐心看下这段逻辑，也可以忽略，大体理解就好。
 * 关于 handler 这个 TokenHandler ，详细见 「5. TokenHandler」 。当然，这也是为什么 GenericTokenParser 叫做通用的原因，而 TokenHandler 处理特定的逻辑
 */
public class GenericTokenParser {

  /**
   * 开始Token字符串
   */
  private final String openToken;
  /**
   * 结束的Token字符串
   */
  private final String closeToken;
  private final TokenHandler handler;

  /**
   * 构造函数
   * @param openToken
   * @param closeToken
   * @param handler
   */
  public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
    this.openToken = openToken;
    this.closeToken = closeToken;
    this.handler = handler;
  }

  /**
   * 这里主要的思路就是，解析出${key} 当中的key，通过handler中来替换
   * @param text
   * @return
   */
  public String parse(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    // search open token
    // 获取第一个openToken在SQL中的位置
    int start = text.indexOf(openToken, 0);
    // start为-1说明SQL中不存在任何参数占位符
    if (start == -1) { // 找不到，直接返回
      return text;
    }
    // 將SQL转换为char数组
    char[] src = text.toCharArray();
    // offset用于记录已解析的#{或者}的偏移量，避免重复解析
    int offset = 0; //其实查找位置
    //结果
    final StringBuilder builder = new StringBuilder();
    // expression为参数占位符中的内容
    StringBuilder expression = null;
    // 遍历获取所有参数占位符的内容，然后调用TokenHandler的handleToken（）方法替换参数占位符
    while (start > -1) {
      if (start > 0 && src[start - 1] == '\\') {
        // this open token is escaped. remove the backslash and continue.
        // 因为 openToken 前面一个位置是 \ 转义字符，所以忽略
        // 添加 [offset, start - offset - 1] 和 openToken 的内容，添加到 builder
        builder.append(src, offset, start - offset - 1).append(openToken);
        offset = start + openToken.length();
      } else {
        // found open token. let's search close token.
        // 创建/重置 expression 对象
        if (expression == null) {
          expression = new StringBuilder();
        } else {
          expression.setLength(0);
        }
        // 添加 offset 和 openToken 之间的内容，添加到 builder 中
        builder.append(src, offset, start - offset);
        offset = start + openToken.length();
        int end = text.indexOf(closeToken, offset);
        while (end > -1) {
          if (end > offset && src[end - 1] == '\\') {
            // this close token is escaped. remove the backslash and continue.
            expression.append(src, offset, end - offset - 1).append(closeToken);
            offset = end + closeToken.length();
            end = text.indexOf(closeToken, offset);
          } else {
            expression.append(src, offset, end - offset);
            offset = end + closeToken.length();
            break;
          }
        }
        if (end == -1) {
          // close token was not found.
          builder.append(src, start, src.length - start);
          offset = src.length;
        } else {
          // 调用TokenHandler的handleToken（）方法替换参数占位符
          builder.append(handler.handleToken(expression.toString()));
          offset = end + closeToken.length();
        }
      }
      start = text.indexOf(openToken, offset);
    }
    if (offset < src.length) {
      builder.append(src, offset, src.length - offset);
    }

    return builder.toString();
  }
}
