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

package com.google.caja.plugin;

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.escaping.Escaping;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.BooleanLiteral;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.Operation;
import com.google.caja.parser.js.Operator;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.TranslatedCode;
import com.google.caja.parser.quasiliteral.ReservedNames;
import com.google.caja.util.Name;
import com.google.caja.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * A series of events that simulate the way a browser processes markup, by
 * creating elements and attributes that interleaved scripts can then mutate.
 *
 * <p>
 * When a browser processes HTML, it generates a DOM from SAX style events, and
 * processes script interleaved in the HTML.
 * This class simulates the HTML processing by interleaving calls to an HTML
 * emitter with embedded script tags.  The HTML emitter creates elements and
 * keeps track of the stack of elements that have not yet been closed, so that
 * content is injected into the right element even though the snippets of HTML
 * between {@code <script>}s are not balanced.
 *
 * @author mikesamuel@gmail.com
 */
final class DomProcessingEvents {
  private final List<DomProcessingEvent> events
      = new ArrayList<DomProcessingEvent>();
  /**
   * True if the last event ends inside a tag as defined by
   * {@link DomProcessingEvent#checkContext}.
   */
  private boolean inTag = false;

  /**
   * Compile DOM processing events from HTML to javascript that emits equivalent
   * HTML.
   * @throws IllegalStateException if the event chain is in the middle of a tag,
   *   i.e. if we have not seen a {@link #finishAttrs} for the last
   *   {@link #begin}.
   */
  void toJavascript(Block out) {
    if (inTag) { throw new IllegalStateException(); }

    optimize();
    BlockAndEmitter blockAndEmitter = new BlockAndEmitter(out);
    boolean inTag = false;
    for (DomProcessingEvent e : events) {
      inTag = e.checkContext(inTag);
      e.toJavascript(blockAndEmitter);
    }
    blockAndEmitter.interruptEmitter();
    if (inTag) { throw new IllegalStateException(); }
  }

  /**
   * Collapse ranges of DOM processing events that will always generate the same
   * HTML.
   *
   * <p>
   * E.g., {@code (Begin 'p') (Begin 'b') (Text 'foo') (End 'b') (End 'p')}
   * => {@code (Begin 'p') (InnerHTML '<b>foo</b>') (End 'p')}.
   *
   * <p>
   * We walk, maintaining a stack of open elements.  When we see a close tag
   * we check whether all the elements between the begin and end
   * (excluding the open tag's attributes) are statically known.  If that's
   * the case, we replace them with an innerHTML node.
   *
   * @throws IllegalStateException if tags are unbalanced.
   */
  private void optimize() {
    List<Pair<Name, Integer>> openTags = new ArrayList<Pair<Name, Integer>>();
    eventloop:
    for (int i = 0; i < events.size(); ++i) {  // Concurrent change below.
      DomProcessingEvent e = events.get(i);
      if (e instanceof FinishAttrsEvent) {
        if (!((FinishAttrsEvent) e).unary) {
          int start = i;
          while (!(events.get(--start) instanceof BeginElementEvent)) {}
          BeginElementEvent bel = (BeginElementEvent) events.get(start);
          openTags.add(Pair.pair(bel.name, i));
        }
      } else if (e instanceof EndElementEvent) {
        EndElementEvent ee = (EndElementEvent) e;
        if (openTags.isEmpty()) { throw new IllegalStateException(); }
        Pair<Name, Integer> top = openTags.remove(openTags.size() - 1);
        if (!top.a.equals(ee.name)) { throw new IllegalStateException(); }
        int start = top.b + 1;
        List<DomProcessingEvent> content = events.subList(start, i);
        if (content.isEmpty()) { continue; }
        int depth = 0;
        for (DomProcessingEvent ce : content) {
          if (ce instanceof BeginElementEvent) {
            ++depth;
          } else if (ce instanceof EndElementEvent
                     || (ce instanceof FinishAttrsEvent
                         && ((FinishAttrsEvent) ce).unary)) {
            --depth;
          }
          if (!ce.canOptimizeToInnerHtml(depth)) { continue eventloop; }
        }
        StringBuilder innerHtml = new StringBuilder();
        for (DomProcessingEvent ce : content) {
          ce.toInnerHtml(innerHtml);
        }
        FilePosition pos = FilePosition.span(
            content.get(0).getFilePosition(),
            content.get(content.size() - 1).getFilePosition());
        content.clear();
        content.add(new InnerHtmlEvent(pos, innerHtml.toString()));
        i = start + 1;
      }
    }
    if (!openTags.isEmpty()) { throw new IllegalStateException("" + openTags); }
  }

  static abstract class DomProcessingEvent {
    /**
     * Update the given javascript node chain to add code that simulates
     * processing of the event.
     */
    abstract void toJavascript(BlockAndEmitter out);
    /**
     * Checks whether or not this event can occur in the current context so that
     * we can report early when DomProcessingEvents's methods are called out
     * of order.
     * @param inTag true iff we are between the < that opens a tag and the >
     *   that closes it.
     * @return true iff the event after this one is inside a tag.
     * @throws IllegalStateException iff event appears in the wrong context.
     */
    abstract boolean checkContext(boolean inTag);
    /**
     * True iff the event's state is entirely statically determinable, so can
     * be replaced by a call to innerHTML when part of a run of events that
     * include entire balanced tag sets and text nodes.
     * @param depth the number of containing elements also being optimized.
     *   This allows us to handle elements that cannot be inserted via innerHTML
     *   due to browser quirks.  See the unittests for specific examples.
     *   If depth is -1, then acts conservatively.
     */
    abstract boolean canOptimizeToInnerHtml(int depth);
    /**
     * Appends HTML to the given buffer.
     * Only callable if {@link #canOptimizeToInnerHtml}.
     */
    abstract void toInnerHtml(StringBuilder out);

    abstract FilePosition getFilePosition();

    @Override
    public String toString() {
      String cn = getClass().getSimpleName();
      StringBuilder sb = new StringBuilder();
      sb.append('(').append(cn, cn.lastIndexOf('.') + 1, cn.length());
      if (canOptimizeToInnerHtml(-1)) {
        sb.append(' ');
        toInnerHtml(sb);
      }
      return sb.append(')').toString();
    }
  }

  /** Creates an element.  Must match up with an {@link EndElementEvent}. */
  static final class BeginElementEvent extends DomProcessingEvent {
    final Name name;
    final FilePosition pos;
    BeginElementEvent(FilePosition pos, Name name) {
      this.name = name;
      this.pos = pos;
    }
    @Override void toJavascript(BlockAndEmitter out) {
      out.emitCall("b", StringLiteral.valueOf(pos, name.getCanonicalForm()));
    }
    @Override boolean canOptimizeToInnerHtml(int depth) {
      String cname = name.getCanonicalForm();
      return depth > 1 || !("option".equals(cname) || "optgroup".equals(cname)
                            || "tbody".equals(cname) || "thead".equals(cname)
                            || "tfoot".equals(cname) || "tr".equals(cname)
                            || "td".equals(cname) || "th".equals(cname)
                            || "param".equals(cname));
      // From http://support.microsoft.com/kb/239832
      // PRB: Error Setting table.innerHTML in Internet Explorer
      // SYMPTOMS
      // Setting table.innerHTML causes the following error message to appear:
      // Unknown runtime error
      //
      // CAUSE
      // The innerHTML property of the TABLE, TFOOT, THEAD, and TR elements are
      // read-only.

      // See bug 845 for the derivation of the list abovee.
    }
    @Override void toInnerHtml(StringBuilder out) {
      out.append('<').append(name);
    }
    @Override boolean checkContext(boolean inTag) {
      if (inTag) { throw new IllegalStateException(this.toString()); }
      return true;
    }
    @Override FilePosition getFilePosition() { return pos; }
  }

  static final class AttribEvent extends DomProcessingEvent {
    final Name name;
    final Expression value;
    AttribEvent(Name name, Expression value) {
      this.name = name;
      this.value = value;
    }
    @Override void toJavascript(BlockAndEmitter out) {
      out.emitCall(
          "a",
          StringLiteral.valueOf(FilePosition.UNKNOWN, name.getCanonicalForm()),
          value);
    }
    @Override boolean canOptimizeToInnerHtml(int depth) {
      return value instanceof StringLiteral;
    }
    @Override void toInnerHtml(StringBuilder out) {
      out.append(' ').append(name).append("=\"");
      Escaping.escapeXml(((StringLiteral) value).getUnquotedValue(), true, out);
      out.append('"');
    }
    @Override boolean checkContext(boolean inTag) {
      if (!inTag) { throw new IllegalStateException(this.toString()); }
      return true;
    }
    @Override FilePosition getFilePosition() { return value.getFilePosition(); }
  }

  /** An event handler, e.g. {@code onclick}. */
  static final class HandlerEvent extends DomProcessingEvent {
    final Name name;
    final Expression fnBody;
    HandlerEvent(Name name, Expression fnBody) {
      this.name = name;
      this.fnBody = fnBody;
    }
    @Override void toJavascript(BlockAndEmitter out) {
      out.emitCall(
          "h",
          StringLiteral.valueOf(FilePosition.UNKNOWN, name.getCanonicalForm()),
          fnBody);
    }
    @Override boolean canOptimizeToInnerHtml(int depth) { return false; }
    @Override void toInnerHtml(StringBuilder out) {
      throw new UnsupportedOperationException();
    }
    @Override boolean checkContext(boolean inTag) {
      if (!inTag) { throw new IllegalStateException(this.toString()); }
      return true;
    }

    @Override FilePosition getFilePosition() {
      return fnBody.getFilePosition();
    }
  }

  static final class FinishAttrsEvent extends DomProcessingEvent {
    /**
     * True if the element will not have a close tag: {@code </foo>}.
     */
    final boolean unary;
    FinishAttrsEvent(boolean unary) { this.unary = unary; }
    @Override void toJavascript(BlockAndEmitter out) {
      out.emitCall("f", new BooleanLiteral(FilePosition.UNKNOWN, unary));
    }
    @Override boolean canOptimizeToInnerHtml(int depth) { return true; }
    @Override void toInnerHtml(StringBuilder out) {
      out.append(unary ? " />" : ">");
    }
    @Override boolean checkContext(boolean inTag) {
      if (!inTag) { throw new IllegalStateException(this.toString()); }
      return false;
    }
    @Override FilePosition getFilePosition() { return FilePosition.UNKNOWN; }
  }

  static final class EndElementEvent extends DomProcessingEvent {
    final Name name;
    final FilePosition pos;
    EndElementEvent(FilePosition pos, Name name) {
      this.name = name;
      this.pos = pos;
    }
    @Override void toJavascript(BlockAndEmitter out) {
      out.emitCall("e", StringLiteral.valueOf(pos, name.getCanonicalForm()));
    }
    @Override boolean canOptimizeToInnerHtml(int depth) { return true; }
    @Override void toInnerHtml(StringBuilder out) {
      out.append("</").append(name).append('>');
    }
    @Override boolean checkContext(boolean inTag) {
      if (inTag) { throw new IllegalStateException(this.toString()); }
      return false;
    }
    @Override FilePosition getFilePosition() { return pos; }
  }

  static abstract class CharDataEvent extends DomProcessingEvent {
    final String text;
    final FilePosition pos;
    CharDataEvent(FilePosition pos, String text) {
      this.pos = pos;
      this.text = text;
    }
    final @Override void toJavascript(BlockAndEmitter out) {
      out.emitCall(getEmitterMethodName(), StringLiteral.valueOf(pos, text));
    }
    @Override boolean canOptimizeToInnerHtml(int depth) { return true; }
    protected abstract String getEmitterMethodName();
    @Override boolean checkContext(boolean inTag) {
      if (inTag) { throw new IllegalStateException(this.toString()); }
      return false;
    }
    @Override FilePosition getFilePosition() { return pos; }
  }

  static final class PcDataEvent extends CharDataEvent {  // Or RCDATA
    PcDataEvent(FilePosition pos, String text) { super(pos, text); }
    @Override protected String getEmitterMethodName() { return "pc"; }
    @Override protected void toInnerHtml(StringBuilder out) {
      Escaping.escapeXml(text, true, out);
    }
  }

  static final class CDataEvent extends CharDataEvent {
    CDataEvent(FilePosition pos, String text) { super(pos, text); }
    @Override protected String getEmitterMethodName() { return "cd"; }
    @Override protected void toInnerHtml(StringBuilder out) {
      out.append(text);
    }
  }

  static final class ScriptBlockEvent extends DomProcessingEvent {
    final Statement stmt;
    ScriptBlockEvent(Statement stmt) { this.stmt = stmt; }
    @Override void toJavascript(BlockAndEmitter out) {
      out.getBlock().appendChild(stmt);
    }
    @Override boolean canOptimizeToInnerHtml(int depth) { return false; }
    @Override void toInnerHtml(StringBuilder out) {
      throw new UnsupportedOperationException();
    }
    @Override boolean checkContext(boolean inTag) {
      if (inTag) { throw new IllegalStateException(this.toString()); }
      return false;
    }
    @Override FilePosition getFilePosition() { return stmt.getFilePosition(); }
  }

  static final class InnerHtmlEvent extends CharDataEvent {
    InnerHtmlEvent(FilePosition pos, String text) { super(pos, text); }
    @Override protected String getEmitterMethodName() { return "ih"; }
    @Override protected void toInnerHtml(StringBuilder out) {
      out.append(text);
    }
  }

  private void addEvent(DomProcessingEvent e) {
    inTag = e.checkContext(inTag);
    events.add(e);
  }

  /** Begin an element when a start tag {@code <foo} is seen. */
  void begin(FilePosition pos, Name name) {
    addEvent(new BeginElementEvent(pos, name));
  }
  /** Adds an attribute to the current element: {@code key="value"}. */
  void attr(Name name, Expression value) {
    addEvent(new AttribEvent(name, value));
  }
  void attr(FilePosition pos, Name name, String value) {
    attr(name, StringLiteral.valueOf(pos, value));
  }
  void handler(Name name, Expression fnBody) {
    addEvent(new HandlerEvent(name, fnBody));
  }
  /** End the attribute list when a {@code >} or {@code />} is seen. */
  void finishAttrs(boolean unary) { addEvent(new FinishAttrsEvent(unary)); }
  /** Textual element content. */
  void pcdata(FilePosition pos, String text) {
    int last = events.size() - 1;
    if (last < 0 || !(events.get(last) instanceof PcDataEvent)) {
      addEvent(new PcDataEvent(pos, text));
    } else {  // Fold runs of text, effectively normalizing.
      events.set(
          last,
          new PcDataEvent(pos, ((PcDataEvent) events.get(last)).text + text));
    }
  }
  void cdata(FilePosition pos, String text) {
    addEvent(new CDataEvent(pos, text));
  }
  /** Ends an element when an end tag {@code </foo>} is seen. */
  void end(FilePosition pos, Name name) {
    addEvent(new EndElementEvent(pos, name));
  }
  /** An interleaved script block. */
  void script(Statement s) { addEvent(new ScriptBlockEvent(s)); }

  private final class BlockAndEmitter {
    /** An expression that will return an HTML emitter. */
    private Expression emitter;
    /** The block that code that simulates HTML processing is built onto. */
    private final Block block;
    /** Depth of function calls in emitter. */
    private int emitterChainDepth = 0;

    BlockAndEmitter(Block block) { this.block = block; }

    Block getBlock() {
      interruptEmitter();
      return block;
    }

    void interruptEmitter() {
      if (emitter != null) {
        block.appendChild(new TranslatedCode(
            new ExpressionStmt(emitter.getFilePosition(), emitter)));
        emitter = null;
        emitterChainDepth = 0;
      }
    }

    Expression getEmitter() {
      // If the compiled html tree is too deep, then some JS interpreters will
      // give up parsing it.
      // SpiderMonkey issues a "Too much recursion" error when the parse tree
      // is too deep, possibly because the parser stack is blowing out.
      if (emitterChainDepth >= 100) { interruptEmitter(); }
      if (emitter == null) {
        emitter = TreeConstruction.memberAccess(
            ReservedNames.IMPORTS, ReservedNames.HTML_EMITTER);
      }
      return emitter;
    }

    void setEmitter(Expression e) { this.emitter = e; }

    void incrementEmitterChainDepth() { ++emitterChainDepth; }

    void emitCall(String emitterMethodName, Expression... methodActuals) {
      Expression[] operands = new Expression[methodActuals.length + 1];
      operands[0] = Operation.createInfix(
           Operator.MEMBER_ACCESS,
           getEmitter(), TreeConstruction.ref(emitterMethodName));
      System.arraycopy(methodActuals, 0, operands, 1, methodActuals.length);
      setEmitter(TreeConstruction.call(operands));
      incrementEmitterChainDepth();
    }
  }
}
