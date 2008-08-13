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
 * These mimic the methods of the same name in jsUnitCore by Ed Hieatt which
 * in turn mimic junit.
 */


function fail(msg) {
  msg = String(msg || '');
  if (typeof console !== 'undefined') {
    console.trace();
    console.log(msg);
  }
  if ('undefined' !== typeof Packages
      && 'function' === typeof Packages.junit.framework.AssertionFailedError) {
    // If run inside Rhino in the presence of junit, throw an error which will
    // escape any exception trapping around the Rhino embedding.
    throw new Packages.junit.framework.AssertionFailedError(msg);
  } else {
    throw new Error(msg);
  }
}

function assertEquals() {
  function commonPrefix(a, b) {
    var n = 0;
    while (n < a.length && n < b.length && a.charAt(n) === b.charAt(n)) {
      ++n;
    }
    return n;
  }
  function commonSuffix(a, b, limit) {
    var i = a.length;
    var j = b.length;
    while (i > limit && j > limit && a.charAt(i - 1) === b.charAt(j - 1)) {
      --i;
      --j;
    }
    return a.length - i;
  }

  var msg;
  var a;
  var b;
  switch (arguments.length) {
    case 2:
      msg = null;
      a = arguments[0];
      b = arguments[1];
      break;
    case 3:
      msg = arguments[0];
      a = arguments[1];
      b = arguments[2];
      break;
    default: fail('missing arguments ' + arguments);
  }
  if (a !== b) {
    if (typeof a === 'string' && typeof b === 'string') {
      var prefix = commonPrefix(a, b);
      var suffix = commonSuffix(a, b, prefix);
      msg = (msg ? msg + ' :: ' : '') + '<<' + a.substring(0, prefix) + '#' +
        a.substring(prefix, a.length - suffix) + '#'  +
        a.substring(a.length - suffix) + '>>' +
        ' != <<' + b.substring(0, prefix) + '#' +
        b.substring(prefix, b.length - suffix) + '#'  +
        b.substring(b.length - suffix) + '>>';
    } else {
      msg = (msg ? msg + ' :: ' : '') + '<<' + a + '>> : ' + (typeof a) +
        ' != <<' + b + '>> : ' + (typeof b);
    }
    fail(msg);
  }
}

function assertTrue() {
  switch (arguments.length) {
    case 1:
      assertEquals(true, arguments[0]);
      break;
    case 2:
      assertEquals(arguments[0], true, arguments[1]);
      break;
    default: fail('missing arguments ' + arguments);
  }
}

function assertFalse() {
  switch (arguments.length) {
    case 1:
      assertEquals(false, arguments[0]);
      break;
    case 2:
      assertEquals(arguments[0], false, arguments[1]);
      break;
    default: fail('missing arguments ' + arguments);
  }
}

function assertLessThan() {
  var msg, a, b;
  switch (arguments.length) {
    case 2:
      msg = null;
      a = arguments[0];
      b = arguments[1];
      break;
    case 3:
      msg = arguments[0];
      a = arguments[1];
      b = arguments[2];
      break;
    default: fail('missing arguments ' + arguments);
  }
  if (!(a < b)) {
    fail((msg ? msg + ' :: ' : '')
         + '!(<<' + a + '>>: ' + (typeof a) + ' < '
         + '<<' + b + '>>: ' + (typeof b) + ')');
  }
}

function assertNull() {
  var msg, a;
  switch (arguments.length) {
    case 1:
      msg = null;
      a = arguments[0];
      break;
    case 2:
      msg = arguments[0];
      a = arguments[1];
      break;
    default: fail('missing arguments ' + arguments);
  }
  if (a !== null) {
    fail((msg ? msg + ' :: ' : '')
         + 'Expected null, not ' + '<<' + a + '>>: ' + (typeof a));
  }
}

function assertThrows() {
  var func, msg;
  switch (arguments.length) {
  case 1:
    func = arguments[0];
    break;
  case 2:
    func = arguments[0];
    msg = arguments[1];
    break;
  default: fail('missing arguments ' + arguments);
  }
  var nil = {};
  var thrown = nil;
  try {
    func();
  } catch (ex) {
    thrown = ex;
  }
  if (thrown !== nil) {
    if (msg) { assertEquals(msg, thrown); }
  } else {
    fail('Did not throw ' + (msg ? msg : 'an exception'));
  }
}
