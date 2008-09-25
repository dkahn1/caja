// Copyright (C) 2007 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.caja.parser.quasiliteral;

import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageType;
import com.google.caja.reporting.MessageTypeInt;

import java.io.IOException;

/**
 * The type of a {Message message} for the JavaScript quasiliteral rewriter.
 *
 * @author mikesamuel@gmail.com
 * @author ihab.awad@gmail.com
 */
public enum RewriterMessageType implements MessageTypeInt {

  VALUEOF_PROPERTY_MUST_NOT_BE_SET(
      "%s: The valueOf property must not be set: %s, %s",
      MessageLevel.FATAL_ERROR),

  VALUEOF_PROPERTY_MUST_NOT_BE_DELETED(
      "%s: The valueOf property must not be deleted: %s, %s",
      MessageLevel.FATAL_ERROR),

  VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE(
      "%s: Variables cannot end in \"__\": %s, %s",
      MessageLevel.FATAL_ERROR),

  PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE(
      "%s: Properties cannot end in \"__\": %s, %s",
      MessageLevel.FATAL_ERROR),

  SELECTORS_CANNOT_END_IN_DOUBLE_UNDERSCORE(
      "%s: Selectors cannot end in \"__\": %s, %s",
      MessageLevel.FATAL_ERROR),

  LABELS_CANNOT_END_IN_DOUBLE_UNDERSCORE(
      "%s: Labels cannot end in \"__\": %s",
      MessageLevel.ERROR),

  INVOKED_INSTANCEOF_ON_NON_FUNCTION(
      "%s: Invoked \"instanceof\" on non-function: %s, %s",
      MessageLevel.FATAL_ERROR),

  MEMBER_KEY_MAY_NOT_END_IN_DOUBLE_UNDERSCORE(
      "%s: Member key may not end in \"__\": %s, %s",
      MessageLevel.FATAL_ERROR),

  DUPLICATE_DEFINITION_OF_LOCAL_VARIABLE(
      "%s: Duplicate definition of local variable: %s",
      MessageLevel.FATAL_ERROR),

  NEW_ON_ARBITRARY_EXPRESSION_DISALLOWED(
      "%s: Cannot invoke \"new\" on an arbitrary expression: %s, %s",
      MessageLevel.FATAL_ERROR),

  CANNOT_ASSIGN_TO_THIS(
      "%s: Cannot assign to \"this\": %s, %s",
      MessageLevel.FATAL_ERROR),

  WITH_BLOCKS_NOT_ALLOWED(
      "%s: \"with\" blocks are not allowed",
      MessageLevel.ERROR),

  NOT_DELETABLE(
      "%s: Invalid operand to delete",
      MessageLevel.ERROR),

  NONASCII_IDENTIFIER(
      "%s: identifier contains non-ASCII characters: %s",
      MessageLevel.FATAL_ERROR),

  ILLEGAL_IDENTIFIER_LEFT_OVER(
      "%s: INTERNAL COMPILER ERROR. "
          + "Illegal identifier passed through from rewriter: %s. "
          + "Please report this error at: http://code.google.com/p/google-caja/issues/",
      MessageLevel.FATAL_ERROR),

  UNSEEN_NODE_LEFT_OVER(
      "%s: INTERNAL COMPILER ERROR. "
          + "Unseen node left over from rewriter. "
          + "Please report this error at: http://code.google.com/p/google-caja/issues/",
      MessageLevel.FATAL_ERROR),

  UNMATCHED_NODE_LEFT_OVER(
      "%s: INTERNAL COMPILER ERROR. "
          + "Node did not match any rules at: %s. "
          + "Please report this error at: http://code.google.com/p/google-caja/issues/",
      MessageLevel.FATAL_ERROR),

  THIS_IN_GLOBAL_CONTEXT(
      "%s: \"this\" cannot be used in the global context",
      MessageLevel.FATAL_ERROR),

  CANNOT_ASSIGN_TO_FREE_VARIABLE(
      "%s: Cannot assign to a free module variable: %s, %s",
      MessageLevel.FATAL_ERROR),

  CANNOT_MASK_IDENTIFIER(
      "%s: Cannot mask identifier \"%s\"", MessageLevel.FATAL_ERROR),

  FOR_IN_NOT_IN_CAJITA(
      "%s: for-in construct not allowed in Cajita: %s, %s",
      MessageLevel.FATAL_ERROR),

  THIS_NOT_IN_CAJITA(
      "%s: 'this' variable not allowed in Cajita: %s, %s",
      MessageLevel.FATAL_ERROR),

  REGEX_LITERALS_NOT_IN_CAJITA(
      "%s: regex literals not allowed in Cajita. "
      + "Call new RegeExp() instead: %s, %s",
      MessageLevel.FATAL_ERROR),

  CANNOT_ASSIGN_TO_FUNCTION_NAME(
      "%s: Cannot assign to a function name: %s, %s",
      MessageLevel.FATAL_ERROR),

  CANNOT_REDECLARE_FUNCTION_NAME(
      "%s: Cannot redeclare a function name: %s, %s",
      MessageLevel.FATAL_ERROR),

  PROTOTYPICAL_INHERITANCE_NOT_IN_CAJITA(
      "%s: Prototypical inheritance is not supported in Cajita. "
      + "The \"prototype\" property of a function is always "
      + "\"undefined\": %s, %s",
      MessageLevel.LINT);

  private final String formatString;
  private final MessageLevel level;
  private final int paramCount;

  RewriterMessageType(String formatString, MessageLevel level) {
    this.formatString = formatString;
    this.level = level;
    this.paramCount = MessageType.formatStringArity(formatString);
  }

  public int getParamCount() {
    return paramCount;
  }

  public void format(MessagePart[] parts, MessageContext context,
                     Appendable out) throws IOException {
    MessageType.formatMessage(formatString, parts, context, out);
  }

  public MessageLevel getLevel() { return level; }
}
