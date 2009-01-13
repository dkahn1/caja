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
 * A set of utility functions that implement browser feature testing to unify
 * certain DOM behaviors, and a set of recommendations about when to use these
 * functions as opposed to the native DOM functions.
 *
 * @author ihab.awad@gmail.com
 * @author jasvir@gmail.com
 * @provides bridal
 * @requires ___, cajita, document, html, navigator
 */

var bridal = (function() {

  ////////////////////////////////////////////////////////////////////////////
  // Private section
  ////////////////////////////////////////////////////////////////////////////

  var features = {
    attachEvent:
        !!(document.createElement('div').attachEvent),
    setAttributeExtraParam:
        new RegExp('Internet Explorer').test(navigator.appName)
  };

  ////////////////////////////////////////////////////////////////////////////
  // Public section
  ////////////////////////////////////////////////////////////////////////////

  var CUSTOM_EVENT_TYPE_SUFFIX = '_custom___';
  function tameEventType(type, opt_isCustom) {
    type = String(type);
    if (endsWith__.test(type)) {
      throw new Error('Invalid event type ' + type);
    }
    if (opt_isCustom
        || html4.atype.SCRIPT !== html4.ATTRIBS['*:on' + type]) {
      type = type + CUSTOM_EVENT_TYPE_SUFFIX;
    }
    return type;
  }

  function untameEventType(type) {
    var suffix = CUSTOM_EVENT_TYPE_SUFFIX;
    var tlen = type.length, slen = suffix.length;
    var end = tlen - slen;
    if (end >= 0 && suffix === type.substring(end)) {
      type = type.substring(0, end);
    }
    return type;
  }

  function initEvent(event, type, bubbles, cancelable) {
    type = tameEventType(type, true);
    bubbles = Boolean(bubbles);
    cancelable = Boolean(cancelable);

    if (event.initEvent) {  // Non-IE
      event.initEvent(type, bubbles, cancelable);
    } else if (bubbles && cancelable) {  // IE
      event.eventType___ = type;
    } else {
      // TODO(mikesamuel): can bubbling and cancelable on events be simulated
      // via http://msdn.microsoft.com/en-us/library/ms533545(VS.85).aspx
      throw new Error(
          'Browser does not support non-bubbling/uncanceleable events');
    }
  }

  function dispatchEvent(element, event) {
    // TODO(mikesamuel): when we change event dispatching to happen
    // asynchronously, we should exempt custom events since those
    // need to return a useful value, and there may be code bracketing
    // them which could observe asynchronous dispatch.

    // "The return value of dispatchEvent indicates whether any of
    //  the listeners which handled the event called
    //  preventDefault. If preventDefault was called the value is
    //  false, else the value is true."
    if (element.dispatchEvent) {
      return Boolean(element.dispatchEvent(event));
    } else {
      // Only dispatches custom events as when tameEventType(t) !== t.
      element.fireEvent('ondataavailable', event);
      return Boolean(event.returnValue);
    }
  }


  /**
   * Add an event listener function to an element.
   *
   * <p>Replaces
   * W3C <code>Element::addEventListener</code> and
   * IE <code>Element::attachEvent</code>.
   *
   * @param {HTMLElement} element a native DOM element.
   * @param {string} type a string identifying the event type.
   * @param {boolean Element::function (event)} handler an event handler.
   * @param {boolean} useCapture whether the user wishes to initiate capture.
   * @return {boolean Element::function (event)} the handler added.  May be
   *     a wrapper around the input.
   */
  function addEventListener(element, type, handler, useCapture) {
    type = String(type);
    var tameType = tameEventType(type);
    if (features.attachEvent) {
      // TODO(ihab.awad): How do we emulate 'useCapture' here?
      if (type !== tameType) {
        var wrapper = eventHandlerTypeFilter(handler, tameType);
        element.attachEvent('ondataavailable', wrapper);
        return wrapper;
      } else {
        element.attachEvent('on' + type, handler);
        return handler;
      }
    } else {
      // FF2 fails if useCapture not passed or is not a boolean.
      element.addEventListener(tameType, handler, useCapture);
      return handler;
    }
  }
  function eventHandlerTypeFilter(handler, tameType) {
    // This does not need to check that handler is callable by untrusted code
    // since the handler will invoke plugin_dispatchEvent which will do that
    // check on the untrusted function reference.
    return function (event) {
      if (tameType === event.eventType___) {
        return handler.call(this, event);
      }
    };
  }

  /**
   * Remove an event listener function from an element.
   *
   * <p>Replaces
   * W3C <code>Element::removeEventListener</code> and
   * IE <code>Element::detachEvent</code>.
   *
   * @param element a native DOM element.
   * @param type a string identifying the event type.
   * @param handler a function acting as an event handler.
   * @param useCapture whether the user wishes to initiate capture.
   */
  function removeEventListener(element, type, handler, useCapture) {
    type = String(type);
    var tameType = tameEventType(type);
    if (features.attachEvent) {
      // TODO(ihab.awad): How do we emulate 'useCapture' here?
      if (tameType !== type) {
        element.detachEvent('ondataavailable', handler);
      } else {
        element.detachEvent('on' + type, handler);
      }
    } else {
      element.removeEventListener(tameType, handler, useCapture);
    }
  }

  /**
   * Clones a node per {@code Node.clone()}.
   * <p>
   * Returns a duplicate of this node, i.e., serves as a generic copy
   * constructor for nodes. The duplicate node has no parent;
   * (parentNode is null.).
   * <p>
   * Cloning an Element copies all attributes and their values,
   * including those generated by the XML processor to represent
   * defaulted attributes, but this method does not copy any text it
   * contains unless it is a deep clone, since the text is contained
   * in a child Text node. Cloning an Attribute directly, as opposed
   * to be cloned as part of an Element cloning operation, returns a
   * specified attribute (specified is true). Cloning any other type
   * of node simply returns a copy of this node.
   * <p>
   * Note that cloning an immutable subtree results in a mutable copy,
   * but the children of an EntityReference clone are readonly. In
   * addition, clones of unspecified Attr nodes are specified. And,
   * cloning Document, DocumentType, Entity, and Notation nodes is
   * implementation dependent.
   *
   * @param {boolean} deep If true, recursively clone the subtree
   * under the specified node; if false, clone only the node itself
   * (and its attributes, if it is an Element).
   *
   * @return {Node} The duplicate node.
   */
  function cloneNode(node, deep) {
    var clone;
    if (!document.all) {  // Not IE 6 or IE 7
      clone = node.cloneNode(deep);
    } else {
      clone = constructClone(node, deep);
    }
    fixupClone(node, clone);
    return clone;
  }

  var endsWith__ = /__$/;
  function constructClone(node, deep) {
    var clone;
    if (node.nodeType === 1) {
      // From http://blog.pengoworks.com/index.cfm/2007/7/16/IE6--IE7-quirks-with-cloneNode-and-form-elements
      //     It turns out IE 6/7 doesn't properly clone some form elements
      //     when you use the cloneNode(true) and the form element is a
      //     checkbox, radio or select element.
      // JQuery provides a clone method which attempts to fix this and an issue
      // with event listeners.  According to the source code for JQuery's clone
      // method ( http://docs.jquery.com/Manipulation/clone#true ):
      //     IE copies events bound via attachEvent when
      //     using cloneNode. Calling detachEvent on the
      //     clone will also remove the events from the orignal
      // We do not need to deal with XHTML DOMs and so can skip the clean step
      // that jQuery does.
      var tagDesc = node.tagName;
      // Copying form state is not strictly mentioned in DOM2's spec of
      // cloneNode, but all implementations do it.  The value copying
      // can be interpreted as fixing implementations' failure to have
      // the value attribute "reflect" the input's value as determined by the
      // value property.
      switch (node.tagName) {
        case 'INPUT':
          tagDesc = '<input name="' + html.escapeAttrib(node.name)
              + '" type="' + html.escapeAttrib(node.type)
              + '" value="' + html.escapeAttrib(node.defaultValue) + '"'
              + (node.defaultChecked ? ' checked="checked">' : '>');
          break;
        case 'OPTION':
          tagDesc = '<option '
              + (node.defaultSelected ? ' selected="selected">' : '>');
          break;
        case 'TEXTAREA':
          tagDesc = '<textarea value="'
              + html.escapeAttrib(node.defaultValue) + '">';
          break;
      }

      clone = document.createElement(tagDesc);

      var attrs = node.attributes;
      for (var i = 0, attr; (attr = attrs[i]); ++i) {
        if (attr.specified && !endsWith__.test(attr.name)) {
          clone.setAttribute(attr.nodeName, attr.nodeValue);
        }
      }
    } else {
      clone = node.cloneNode(false);
    }
    if (deep) {
      // TODO(mikesamuel): should we whitelist nodes here, to e.g. prevent
      // untrusted code from reloading an already loaded script by cloning
      // a script node that somehow exists in a tree accessible to it?
      for (var child = node.firstChild; child; child = child.nextSibling) {
        var cloneChild = constructClone(child, deep);
        clone.appendChild(cloneChild);
      }
    }
    return clone;
  }

  function fixupClone(node, clone) {
    for (var child = node.firstChild, cloneChild = clone.firstChild; cloneChild;
         child = child.nextSibling, cloneChild = cloneChild.nextSibling) {
      fixupClone(child, cloneChild);
    }
    if (node.nodeType === 1) {
      switch (node.tagName) {
        case 'INPUT':
          clone.value = node.value;
          clone.checked = node.checked;
          break;
        case 'OPTION':
          clone.selected = node.selected;
          clone.value = node.value;
          break;
        case 'TEXTAREA':
          clone.value = node.value;
          break;
      }
    }

    // Do not copy listeners since DOM2 specifies that only attributes and
    // children are copied, and that children should only be copied if the
    // deep flag is set.
    // The children are handled in constructClone.
    var originalAttribs = node.attributes___;
    if (originalAttribs) {
      var attribs = {};
      clone.attributes___ = attribs;
      cajita.forOwnKeys(originalAttribs, ___.func(function (k, v) {
        switch (typeof v) {
          case 'string': case 'number': case 'boolean':
            attribs[k] = v;
            break;
        }
      }));
    }
  }

  /**
   * Create a <code>style</code> element for a document containing some
   * specified CSS text. Does not add the element to the document: the client
   * may do this separately if desired.
   *
   * <p>Replaces directly creating the <code>style</code> element and
   * populating its contents.
   *
   * @param document a DOM document.
   * @param cssText a string containing a well-formed stylesheet production.
   * @return a <code>style</code> element for the specified document.
   */
  function createStylesheet(document, cssText) {
    // Courtesy Stoyan Stefanov who documents the derivation of this at
    // http://www.phpied.com/dynamic-script-and-style-elements-in-ie/ and
    // http://yuiblog.com/blog/2007/06/07/style/
    var styleSheet = document.createElement('style');
    styleSheet.setAttribute('type', 'text/css');
    if (styleSheet.styleSheet) {   // IE
      styleSheet.styleSheet.cssText = cssText;
    } else {                // the world
      styleSheet.appendChild(document.createTextNode(cssText));
    }
    return styleSheet;
  }

  /**
   * Set an attribute on a DOM node.
   *
   * <p>Replaces DOM <code>Node::setAttribute</code>.
   *
   * @param node a DOM document.
   * @param name a string containing the name of an attribute.
   * @param value a string containing the value of an attribute.
   */
  function setAttribute(node, name, value) {
    if (name === 'style'
        && (typeof node.style.cssText) === 'string') {
      // Setting the 'style' attribute does not work for IE, but
      // setting cssText works on IE 6, Firefox, and IE 7.
      node.style.cssText = value;
    } else if (name === 'class') {
      node.className = value;
    } else if (features.setAttributeExtraParam) {
      node.setAttribute(name, value, 0);
    } else {
      node.setAttribute(name, value);
    }
    return value;
  }

  return {
    addEventListener: addEventListener,
    removeEventListener: removeEventListener,
    initEvent: initEvent,
    dispatchEvent: dispatchEvent,
    cloneNode: cloneNode,
    createStylesheet: createStylesheet,
    setAttribute: setAttribute,
    untameEventType: untameEventType
  };
})();
