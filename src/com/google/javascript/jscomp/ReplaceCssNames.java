/*
 * Copyright 2009 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Joiner;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.base.format.SimpleFormat;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.nullness.Nullable;

/**
 * ReplaceCssNames replaces occurrences of goog.getCssName('foo') with a shorter version from the
 * passed in renaming map. There are two styles of operation: for 'BY_WHOLE' we look up the whole
 * string in the renaming map. For 'BY_PART', all the class name's components, separated by '-', are
 * renamed individually and then recombined.
 *
 * <p>Given the renaming map:
 *
 * <pre><code>
 *   {
 *     once:  'a',
 *     upon:  'b',
 *     atime: 'c',
 *     long:  'd',
 *     time:  'e',
 *     ago:   'f'
 *   }
 * </code></pre>
 *
 * <p>The following outputs are expected with the 'BY_PART' renaming style:
 *
 * <pre><code>
 * goog.getCssName('once') -> 'a'
 * goog.getCssName('once-upon-atime') -> 'a-b-c'
 *
 * var baseClass = goog.getCssName('long-time');
 * el.className = goog.getCssName(baseClass, 'ago');
 * ->
 * var baseClass = 'd-e';
 * el.className = baseClass + '-f';
 * </code></pre>
 *
 * <p>However if we have the following renaming map with the 'BY_WHOLE' renaming style:
 *
 * <pre><code>
 *   {
 *     once: 'a',
 *     upon-atime: 'b',
 *     long-time: 'c',
 *     ago: 'd'
 *   }
 * </code></pre>
 *
 * <p>Then we would expect:
 *
 * <pre><code>
 * goog.getCssName('once') -> 'a'
 *
 * var baseClass = goog.getCssName('long-time');
 * el.className = goog.getCssName(baseClass, 'ago');
 * ->
 * var baseClass = 'c';
 * el.className = baseClass + '-d';
 * </code></pre>
 *
 * <p>In addition, the CSS names before replacement can optionally be gathered.
 */
class ReplaceCssNames implements CompilerPass {

  // This is used only for qualified name comparison, so it's OK to build it with IR instead of
  // AstFactory. Type information isn't important here.
  static final Node GET_CSS_NAME_FUNCTION = IR.getprop(IR.name("goog"), "getCssName");

  static final DiagnosticType INVALID_NUM_ARGUMENTS_ERROR =
      DiagnosticType.error(
          "JSC_GETCSSNAME_NUM_ARGS",
          "goog.getCssName called with \"{0}\" arguments, expected 1 or 2.");

  static final DiagnosticType STRING_LITERAL_EXPECTED_ERROR =
      DiagnosticType.error(
          "JSC_GETCSSNAME_STRING_LITERAL_EXPECTED",
          "goog.getCssName called with invalid argument, string literal "
              + "expected.  Was \"{0}\".");

  static final DiagnosticType UNEXPECTED_STRING_LITERAL_ERROR =
      DiagnosticType.error(
          "JSC_GETCSSNAME_UNEXPECTED_STRING_LITERAL",
          "goog.getCssName called with invalid arguments, string literal "
              + "passed as first of two arguments.  Did you mean "
              + "goog.getCssName(\"{0}-{1}\")?");

  static final DiagnosticType NESTED_CALL_ERROR =
      DiagnosticType.error(
          "JSC_GETCSSNAME_NESTED_CALL", "goog.getCssName: nested call is not allowed.");

  static final DiagnosticType UNKNOWN_SYMBOL_WARNING =
      DiagnosticType.warning(
          "JSC_GETCSSNAME_UNKNOWN_CSS_SYMBOL",
          "goog.getCssName called with unrecognized symbol \"{0}\" in class " + "\"{1}\".");

  private final AbstractCompiler compiler;
  private final AstFactory astFactory;

  private final Map<String, Integer> cssNames;

  private @Nullable CssRenamingMap symbolMap;

  private final Set<String> skiplist;

  ReplaceCssNames(
      AbstractCompiler compiler,
      @Nullable CssRenamingMap symbolMap,
      @Nullable Map<String, Integer> cssNames,
      @Nullable Set<String> skiplist) {
    this.compiler = compiler;
    this.astFactory = compiler.createAstFactory();
    this.symbolMap = symbolMap;
    this.cssNames = cssNames;
    this.skiplist = skiplist;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new FindSetCssNameTraversal());
    List<GetCssNameInstance> getCssNameInstances = gatherGetCssNameInstances(root);
    for (GetCssNameInstance getCssNameInstance : getCssNameInstances) {
      getCssNameInstance.replaceWithExpression();
    }
  }

  /**
   * Create a list of objects representing all the `goog.getCssName()` calls in the AST.
   *
   * <p>The elements in this list will be in depth-first left-to-right order. So, if you perform
   * actions on them in this order you'll affect nested calls before the containing calls. e.g.
   *
   * <pre><code>
   *   goog.getCssName(goog.getCssName('me-first'), 'me-second')
   * </code></pre>
   */
  private List<GetCssNameInstance> gatherGetCssNameInstances(Node root) {
    GatherCssNamesTraversal gatherCssNamesTraversal = new GatherCssNamesTraversal();
    NodeTraversal.traverse(compiler, root, gatherCssNamesTraversal);
    return gatherCssNamesTraversal.listOfCssNameInstances;
  }

  // This is used only for qualified name comparison, so it's OK to build it with IR instead of
  // AstFactory. Type information isn't important here.
  private static final Node GOOG_SET_CSS_NAME_MAPPING =
      IR.getprop(IR.name("goog"), "setCssNameMapping");

  private class FindSetCssNameTraversal extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!n.isCall() || !parent.isExprResult()) {
        return;
      }
      Node callee = n.getFirstChild();
      if (!callee.matchesQualifiedName(GOOG_SET_CSS_NAME_MAPPING)) {
        return;
      }
      CssRenamingMap cssRenamingMap =
          ProcessClosurePrimitives.processSetCssNameMapping(compiler, n, parent);
      symbolMap = cssRenamingMap;
      compiler.reportChangeToEnclosingScope(parent);
      parent.detach();
    }
  }

  private class GatherCssNamesTraversal extends AbstractPostOrderCallback {
    final List<GetCssNameInstance> listOfCssNameInstances = new ArrayList<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (isGetCssNameCall(n)) {
        GetCssNameInstance getCssNameInstance = createGetCssNameInstance(n);
        if (getCssNameInstance.isValid()) {
          listOfCssNameInstances.add(getCssNameInstance);
        } else {
          compiler.report(getCssNameInstance.getValidationError());
        }
      }
    }
  }

  private boolean isGetCssNameCall(Node node) {
    return node.isCall() && node.getFirstChild().matchesQualifiedName(GET_CSS_NAME_FUNCTION);
  }

  private GetCssNameInstance createGetCssNameInstance(Node n) {
    return new GetCssNameInstance(n, validateGetCssNameCall(n));
  }

  /** Represents a `goog.getCssName()` call. */
  private class GetCssNameInstance {

    /**
     * The CALL node representing `goog.getCssName()`.
     *
     * <p>We shouldn't store final pointers to any of the children of this node, because they might
     * be changed between creation of this object and performance of other methods.
     */
    final Node callNode;

    /** Non-null if the shape of the function call was invalid, when this object was created. */
    final JSError validationError;

    GetCssNameInstance(Node callNode, JSError validationError) {
      this.callNode = callNode;
      this.validationError = validationError;
    }

    boolean isValid() {
      return validationError == null;
    }

    JSError getValidationError() {
      checkNotNull(validationError, "No validation error found: %s", callNode);
      return validationError;
    }

    /** Replace this `goog.getCssName()` call in the AST with a string typed expression. */
    void replaceWithExpression() {
      checkState(isValid(), "not a valid goog.getCssName() call: %s", callNode);
      checkNotNull(callNode.getParent(), "already replaced: %s", callNode);
      final int childCount = callNode.getChildCount();
      switch (childCount) {
        case 2:
          replaceWithConvertedSingleArg();
          break;
        case 3:
          replaceWithConcatenatedArgs();
          break;
        default:
          throw new IllegalStateException(
              SimpleFormat.format("invalid number of children: %s for: %s", childCount, callNode));
      }
    }

    private void replaceWithConvertedSingleArg() {
      // `goog.getCssName('some-literal')` -> `'mapped-literal'`
      final Node stringLitArg = checkNotNull(callNode.getSecondChild(), callNode);
      checkState(stringLitArg.isStringLit(), "not a string literal: %s", stringLitArg);
      stringLitArg.detach();
      processStringNode(stringLitArg);
      callNode.replaceWith(stringLitArg);
      compiler.reportChangeToEnclosingScope(stringLitArg);
    }

    private void replaceWithConcatenatedArgs() {
      // `goog.getCssName(someExpr, 'some-literal')` -> `someExpr + '-mapped-literal'`
      final Node firstArg = checkNotNull(callNode.getSecondChild(), callNode);
      final Node secondArg = checkNotNull(firstArg.getNext(), firstArg);
      firstArg.detach();
      secondArg.detach();
      processStringNode(secondArg);
      secondArg.setString("-" + secondArg.getString());
      final Node replacement = astFactory.createAdd(firstArg, secondArg).srcref(callNode);
      callNode.replaceWith(replacement);
      compiler.reportChangeToEnclosingScope(replacement);
    }
  }

  private @Nullable JSError validateGetCssNameCall(Node callNode) {
    int childCount = callNode.getChildCount();
    switch (childCount) {
      case 2:
        return validateSingleArgGetCssNameCall(callNode);
      case 3:
        return validateTwoArgGetCssNameCall(callNode);
      default:
        return JSError.make(callNode, INVALID_NUM_ARGUMENTS_ERROR, String.valueOf(childCount));
    }
  }

  private @Nullable JSError validateSingleArgGetCssNameCall(Node callNode) {
    // `goog.getCssName('css-name')`
    final Node stringLiteralArg = checkNotNull(callNode.getLastChild());
    if (!stringLiteralArg.isStringLit()) {
      return JSError.make(
          callNode, STRING_LITERAL_EXPECTED_ERROR, stringLiteralArg.getToken().toString());
    }
    return null;
  }

  private @Nullable JSError validateTwoArgGetCssNameCall(Node callNode) {
    // `goog.getCssName(BASE_CSS_NAME, 'css-suffix')`
    // The first child is the callee, so the first argument is the second child
    final Node firstArg = checkNotNull(callNode.getSecondChild());
    final Node secondArg = checkNotNull(firstArg.getNext());

    if (!secondArg.isStringLit()) {
      return JSError.make(callNode, STRING_LITERAL_EXPECTED_ERROR, secondArg.getToken().toString());
    }
    if (firstArg.isStringLit()) {
      return JSError.make(
          callNode, UNEXPECTED_STRING_LITERAL_ERROR, firstArg.getString(), secondArg.getString());
    }
    if (isGetCssNameCall(firstArg)) {
      // Disallow `goog.getCssName(goog.getCssName('n1'), 'n2')`.
      // Disallowing this is a bit arbitrary and is done for historical reasons.
      // The 2-argument form results in more JS code shipped to the browser and
      // forces users to have camelCase CSS names, which contravenes normal CSS style.
      // Since we'd like to stop supporting the 2-argument form, we don't want to
      // start allowing any form of it that was previously an error.
      return JSError.make(firstArg, NESTED_CALL_ERROR);
    }

    return null;
  }

  /**
   * Processes a string argument to goog.getCssName(). The string will be renamed based off the
   * symbol map. If there is no map or any part of the name can't be renamed, a warning is reported
   * to the compiler and the node is left unchanged.
   *
   * <p>If the type is unexpected then an error is reported to the compiler.
   *
   * @param n The string node to process.
   */
  private void processStringNode(Node n) {
    String name = n.getString();
    if (skiplist != null && skiplist.contains(name)) {
      // We apply the skiplist before splitting on dashes, and not after.
      // External substitution maps should do the same.
      return;
    }
    String[] parts = name.split("-");
    if (symbolMap != null) {
      String replacement = null;
      switch (symbolMap.getStyle()) {
        case BY_WHOLE:
          replacement = symbolMap.get(name);
          if (replacement == null) {
            compiler.report(JSError.make(n, UNKNOWN_SYMBOL_WARNING, name, name));
            return;
          }
          break;
        case BY_PART:
          String[] replaced = new String[parts.length];
          for (int i = 0; i < parts.length; i++) {
            String part = symbolMap.get(parts[i]);
            if (part == null) {
              // If we can't encode all parts, don't encode any of it.
              compiler.report(JSError.make(n, UNKNOWN_SYMBOL_WARNING, parts[i], name));
              return;
            }
            replaced[i] = part;
          }
          replacement = Joiner.on("-").join(replaced);
          break;
      }
      n.setString(replacement);
    }
    if (cssNames != null) {
      // We still want to collect statistics even if we've already
      // done the full replace. The statistics are collected on a
      // per-part basis.
      for (String element : parts) {
        int count = cssNames.getOrDefault(element, 0);
        cssNames.put(element, count + 1);
      }
    }
  }
}
