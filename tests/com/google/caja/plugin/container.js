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

/**
 * @fileoverview
 * Sets up a caja container for hosting the cajoled tests.
 * Requires that caja.js, asserts.js and browser-stubs.js be loaded first.
 */

var imports = ___.copy(___.sharedImports);
var exports = {};
imports.fail = ___.simpleFrozenFunc(fail);
imports.assertEquals = ___.simpleFrozenFunc(assertEquals);
imports.assertTrue = ___.simpleFrozenFunc(assertTrue);
imports.assertFalse = ___.simpleFrozenFunc(assertFalse);
imports.document = document;
imports.console = console;
imports.valija = valijaMaker(imports);
___.grantRead(imports, 'valija');
___.simpleFrozenFunc(console.log);
// Included in order to test this function;
// stamp should never be made available to real caja code.
imports.stamp = ___.simpleFrozenFunc(___.stamp);
imports.exports = exports;
___.getNewModuleHandler().setImports(imports);
if ('undefined' !== typeof Packages) {
  // Propagate test failures upwards.
  ___.getNewModuleHandler().handleUncaughtException = (function (orig) {
        return function (exception, onerror, source, lineNum) {
          // Propagate test failures outside script blocks.
          // See fail() in asserts.js.
          if (exception
              instanceof Packages.junit.framework.AssertionFailedError) {
            throw exception;
          }
          return orig.call(caja.USELESS, exception, onerror, source, lineNum);
        };
      })(___.getNewModuleHandler().handleUncaughtException);
}
imports.htmlEmitter___ = new HtmlEmitter(document.getElementById("test-test"));
