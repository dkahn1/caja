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

/**
 * @fileoverview
 * Supporting scripts for the cajoling testbed.
 *
 * <p>
 * This supports an input forms that can be instantiated multiple times to
 * simulate multiple gadgets in the same frame.
 * Different forms are distinguished by a unique suffix, and use the following
 * identifiers:
 * <dl>
 *   <dt><code>'cajolerForm' + uiSuffix</code></dt>
 *   <dd>The FORM containing the testbed source code</dd>
 *   <dt><code>'messages' + uiSuffix</code></dt>
 *   <dd>Messages from the Cajoler's MessageQueue with snippets.</dd>
 *   <dt><code>'output' + uiSuffix</code></dt>
 *   <dd>Container for cajoled output.</dd>
 *   <dt><code>'eval-results' + uiSuffix</code></dt>
 *   <dd>Container for result of last expression in source code.</dd>
 *   <dt><code>'caja-stacks' + uiSuffix</code></dt>
 *   <dd>Parent of container for debug mode stack traces a la
 *     <tt>caja-debugmode.js</tt>.</dd>
 *   <dt><code>'caja-stack' + uiSuffix</code></dt>
 *   <dd>Container for debug mode stack traces.</dd>
 *   <dt><code>'caja-html' + uiSuffix</code></dt>
 *   <dd>Container for HTML rendered by cajoled code.</dd>
 * </dl>
 * All UI suffixes start with a '.' which is allowed in XML IDs and CLASSes.
 *
 * @author mikesamuel@gmail.com
 */

if ('undefined' === typeof prettyPrintOne) {
  // So it works without prettyprinting when disconnected from the network.
  prettyPrintOne = function (html) { return html; };
}

/**
 * Returns an instance of CajaApplet that exposes public methods as javascript
 * methods.
 * @see CajaApplet.java
 * @return {CajaApplet}
 */
function getCajoler() {
  return document.applets.cajoler;
}

/**
 * Reads caja code and configuration from the testbed form, cajoles it, and
 * displays the output in the current HTML page.
 */
var cajole = (function () {
  /**
   * Converts a plain text string to an HTML encoded string suitable for
   * inclusion in PCDATA or an HTML attribute value.
   * @param {string} s
   * @return {string}
   */
  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/\042/g, '&quot;');
  }

  /**
   * Extract cajoled ecmascript from DefaultGadgetRewriter's output.
   * This removes the envelope created by
   * DefaultGadgetRewriter.rewriteContent(String).
   * @param {string} htmlText from the cajoler
   * @param {string} uiSuffix suffix of testbed identifiers as described above.
   */
  function loadCaja(htmlText, uiSuffix) {
    var m = htmlText.match(
        /^\s*<script\b[^>]*>([\s\S]*)<\/script\b[^>]*>\s*$/i);
    if (m) {
      var script = m[1];
      var imports = getImports(uiSuffix);

      imports.clearHtml___();
      var stackTrace = document.getElementById('caja-stacks' + uiSuffix)
      stackTrace.style.display = 'none';

      // Set up the module handler
      ___.getNewModuleHandler().setImports(imports);

      // Load the script
      try {
        eval(script);
      } catch (ex) {
        var cajaStack = ex.cajaStack___
            && ___.unsealCallerStack(ex.cajaStack___);
        if (cajaStack) {
          stackTrace.style.display = '';
          document.getElementById('caja-stack' + uiSuffix).appendChild(
              document.createTextNode(cajaStack.join('\n')));
        }
        throw ex;
      }
    } else {
      (typeof console !== 'undefined')
      && console.warn('Failed to eval cajoled output %s', html);
    }
  }

  function cajole(form) {
    var inputs = form.elements;
    var result = getCajoler().cajole(
        inputs.src.value.replace(/^\s+|\s+$/g, ''),
        Boolean(inputs.embeddable.checked),
        Boolean(inputs.debugSymbols.checked));
    var cajoledOutput = result[0];
    var messages = String(result[1]);

    var uiSuffix = form.id.replace(/^[^\.]+/, '');
    if (cajoledOutput !== null) {
      cajoledOutput = String(cajoledOutput);
      document.getElementById('output' + uiSuffix).innerHTML
          = prettyPrintOne(escapeHtml(cajoledOutput));

      loadCaja(cajoledOutput, uiSuffix);
    } else {
      document.getElementById('output' + uiSuffix).innerHTML
          = '<center class="failure">Failed<\/center>';
    }
    document.getElementById('messages' + uiSuffix).innerHTML = messages || '';
  }

  return cajole;
})();

/**
 * Concatenates all text node leaves of the given DOM subtree to produce the
 * equivalent of IE's innerText attribute.
 */
var innerText = (function () {
  function innerText(node) {
    var s = [];
    innerTextHelper(node, s);
    return s.join('');
  }

  function innerTextHelper(node, buf) {
    for (var child = node.firstChild; child; child = child.nextSibling) {
      switch (child.nodeType) {
        case 3:
          buf.push(child.nodeValue); break;
        case 1:
          if ('BR' === child.nodeName) {
            buf.push('\n');
            break;
          }
          // fall through
        default:
          innerTextHelper(child, buf);
          break;
      }
    }
  }

  return innerText;
})();


/**
 * Gets the fake global scope for the testbed gadget with the given ui suffix.
 */
var getImports = (function () {
  var importsByUiSuffix = {};

  /**
   * Returns a string describing the type of the given object.
   * For a primitive, uses typeof, but for a constructed object tries to
   * determine the constructor name.
   */
  function typeString(o) {
    if (typeof o === 'object') {
      if (o === null) { return 'null'; }
      var ctor = ___.directConstructor(o);
      var name;
      if (ctor) {
        name = ctor.NAME___;
        if (!name && ('name' in ctor) && !___.hasOwnProp(ctor, 'name')) {
          name = ctor.name;
        }
        if (name) { return String(name); }
      }
    }
    return typeof o;
  }

  /** Escape one character by javascript string literal rules. */
  function escapeOne(ch) {
    var i = ch.charCodeAt(0);
    if (i < 0x80) {
      switch (i) {
        case 9: return '\t';
        case 0x0a: return '\\n';
        case 0x0d: return '\\r';
        case 0x22: return '\\"';
        case 0x5c: return '\\\\';
        default: return (i < 0x10 ? '\\x0' : '\\x') + i.toString(16);
      }
    }
    var hex = i.toString(16);
    while (hex.length < 4) { hex = '0' + hex; }
    return '\\u' + hex;
  }

  /** Builds part of the repr of a JSON map. */
  function reprKeyValuePair(els) {
    return ___.simpleFunc(function (k, v) {
      els.push(repr(k) + ': ' + repr(v));
    });
  }

  /**
   * Like the python function, but produces a debugging string instead of
   * one that can be evaled.
   */
  function repr(o) {
    switch (typeof o) {
      case 'string':
        return ('"'
                + o.replace(/[^\x20\x21\x23-\x5b\x5d-\x7f]/g, escapeOne)
                + '"');
      case 'object': case 'function':
        if (o === null) { break; }
        if (caja.isJSONContainer(o)) {
          var els = [];
          if ('length' in o
              && !(Object.prototype.propertyIsEnumerable.call(o, 'length'))
              ) {
            for (var i = 0; i < o.length; ++i) {
              els.push(repr(o[i]));
            }
            return '[' + els.join(', ') + ']';
          } else {
            caja.each(o, reprKeyValuePair(els));
            return els.length ? '{ ' + els.join(', ') + ' }' : '{}';
          }
        }
        return '\u00ab' + o + '\u00bb';
    }
    return String(o);
  }

  /** Javascript support for ExpressionLanguageStage.java */
  function yielder(uiSuffix) {
    return function yield(o) {
      var type = document.createElement('span');
      type.className = 'type';
      type.appendChild(document.createTextNode(typeString(o)));

      var entry = document.createElement('div');
      entry.className = 'result';
      entry.appendChild(type);
      entry.appendChild(document.createTextNode(repr(o)));

      document.getElementById('eval-results' + uiSuffix).appendChild(entry);
    };
  }

  function getImports(uiSuffix) {
    if (uiSuffix in importsByUiSuffix) {
      return importsByUiSuffix[uiSuffix];
    }

    var testImports = ___.copy(___.sharedImports);
    testImports.yield = ___.primFreeze(___.simpleFunc(yielder(uiSuffix)));
    attachDocumentStub(
         '-xyz___',
         {
           rewrite:
               function (uri, mimeType) {
                 if (!/^https?:\/\//i.test(uri)) { return null; }
                 return 'http://gadget-proxy/?url=' + encodeURIComponent(uri)
                     + '&mimeType=' + encodeURIComponent(mimeType);
               }
         },
         testImports);
    testImports.clearHtml___ = function () {
      var htmlContainer = document.getElementById('caja-html' + uiSuffix);
      htmlContainer.innerHTML = '<center style="color: gray">eval<\/center>';
      testImports.htmlEmitter___ = new HtmlEmitter(htmlContainer);
    };
    /**
     * Put styles inside a node that is cleared for each gadget so that
     * styles don't persist across invocations of cajole.
     */
    testImports.getCssContainer___ = function () {
      return document.getElementById('caja-html' + uiSuffix);
    };
    return importsByUiSuffix[uiSuffix] = testImports;
  }

  return getImports;
})();


/**
 * Copies the given DOM node and rewrites IDs to be unique as a poor man's
 * Maps templates.
 */
function renderTemplate(domTree, domSuffix) {
  function fixNamesAndIds(node, inForm) {
    if (node.nodeType === 1) {
      if (node.hasAttribute('id')) {
        node.setAttribute('id', node.getAttribute('id') + domSuffix);
      }
      if (!inForm) {
        if (node.hasAttribute('name')) {
          node.setAttribute('name', node.getAttribute('name') + domSuffix);
        }
      }
      inForm = 'FORM' === node.nodeName;
    }
    for (var child = node.firstChild; child; child = child.nextSibling) {
      fixNamesAndIds(child, inForm);
    }
  }
  domTree = domTree.cloneNode(true);
  fixNamesAndIds(domTree, false);
  return domTree;
}


/** UI suffixes of all registered testbeds. */
var testbeds = [];
function registerTestbed(uiSuffix) {
  testbeds.push(uiSuffix);
}

function initTestbeds() {
  for (var i = 0; i < testbeds.length; ++i) {
    getImports(testbeds[i]).clearHtml___();
  }
}
