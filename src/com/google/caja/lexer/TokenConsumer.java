// Copyright (C) 2008 Google Inc.
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

package com.google.caja.lexer;

/**
 * Receives a set of tokens corresponding to a rendered parse tree.
 *
 * @author mikesamuel@gmail.com
 */
public interface TokenConsumer {
  /**
   * Marks tokens {@link #consume consumed} before the next call as falling in 
   * this range of file positions.
   * @param pos null indicates don't know.
   */
  void mark(FilePosition pos);

  /** Receives tokens from rendered parse trees. */
  void consume(String text);

  /** Called when no more tokens are available. */
  void noMoreTokens();
}
