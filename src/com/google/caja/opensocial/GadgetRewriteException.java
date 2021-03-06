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

package com.google.caja.opensocial;

/**
 * Thrown when there is a problem in parsing or rewriting a gadget.
 * FIXME(msamuel): Why isn't this a Caja exception?
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class GadgetRewriteException extends Exception {
  public GadgetRewriteException() {
    this(null, null);
  }

  public GadgetRewriteException(String message) {
    this(message, null);
  }

  public GadgetRewriteException(Throwable cause) {
    this(null, cause);
  }

  public GadgetRewriteException(String message, Throwable cause) {
    super(message, cause);
  }
}
