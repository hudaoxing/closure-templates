/*
 * Copyright 2016 Google Inc.
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

package com.google.template.soy.passes;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.truth.StringSubject;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.base.internal.IncrementingIdGenerator;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class HtmlRewritePassTest {

  @Test
  public void testTags() {
    TemplateNode node = runPass("<div></div>");
    assertThat(node.getChild(0)).isInstanceOf(HtmlOpenTagNode.class);
    assertThat(node.getChild(1)).isInstanceOf(HtmlCloseTagNode.class);
    assertThatSourceString(node).isEqualTo("<div></div>");
    assertThatASTString(node)
        .isEqualTo(
            "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");
  }

  @Test
  public void testAttributes() {
    TemplateNode node = runPass("<div class=\"foo\"></div>");
    assertThatSourceString(node).isEqualTo("<div class=\"foo\"></div>");
    String structure =
        ""
            + "HTML_OPEN_TAG_NODE\n"
            + "  RAW_TEXT_NODE\n"
            + "  HTML_ATTRIBUTE_NODE\n"
            + "    RAW_TEXT_NODE\n"
            + "    HTML_ATTRIBUTE_VALUE_NODE\n"
            + "      RAW_TEXT_NODE\n"
            + "HTML_CLOSE_TAG_NODE\n"
            + "  RAW_TEXT_NODE\n"
            + "";
    assertThatASTString(node).isEqualTo(structure);

    // test alternate quotation marks

    node = runPass("<div class='foo'></div>");
    assertThatSourceString(node).isEqualTo("<div class='foo'></div>");
    assertThatASTString(node).isEqualTo(structure);

    node = runPass("<div class=foo></div>");
    assertThatSourceString(node).isEqualTo("<div class=foo></div>");
    assertThatASTString(node).isEqualTo(structure);

    // This is a tricky case, according to the spec the '/' belongs to the attribute, not the tag
    node = runPass("<input class=foo/>");
    assertThatSourceString(node).isEqualTo("<input class=foo/>");
    HtmlOpenTagNode openTag = (HtmlOpenTagNode) node.getChild(0);
    assertThat(openTag.isSelfClosing()).isFalse();
    HtmlAttributeValueNode attributeValue =
        (HtmlAttributeValueNode) ((HtmlAttributeNode) openTag.getChild(1)).getChild(1);
    assertThat(attributeValue.getQuotes()).isEqualTo(HtmlAttributeValueNode.Quotes.NONE);
    assertThat(((RawTextNode) attributeValue.getChild(0)).getRawText()).isEqualTo("foo/");
  }

  @Test
  public void testLetAttributes() {
    TemplateNode node = runPass("{let $foo kind=\"attributes\"}class='foo'{/let}");
    assertThatSourceString(node).isEqualTo("{let $foo kind=\"attributes\"}class='foo'{/let}");
    String structure =
        ""
            + "LET_CONTENT_NODE\n"
            + "  HTML_ATTRIBUTE_NODE\n"
            + "    RAW_TEXT_NODE\n"
            + "    HTML_ATTRIBUTE_VALUE_NODE\n"
            + "      RAW_TEXT_NODE\n";
    assertThatASTString(node).isEqualTo(structure);
  }

  @Test
  public void testSelfClosingTag() {
    TemplateNode node = runPass("<input/>");
    assertThatSourceString(node).isEqualTo("<input/>");

    // NOTE: the whitespace difference
    node = runPass("<input />");
    assertThatSourceString(node).isEqualTo("<input/>");
  }

  @Test
  public void testTextNodes() {
    TemplateNode node = runPass("x x<div>content</div> <div>{sp}</div>");
    assertThatSourceString(node).isEqualTo("x x<div>content</div> <div> </div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "RAW_TEXT_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");
  }

  @Test
  public void testUnquotedAttributeValue() {
    TemplateNode node = runPass("<img class=foo />");
    assertThat(((HtmlOpenTagNode) node.getChild(0)).isSelfClosing()).isTrue();
    node = runPass("<img class=foo/>");
    assertThat(((HtmlOpenTagNode) node.getChild(0)).isSelfClosing()).isFalse();
    node = runPass("<img class/>");
    assertThat(((HtmlOpenTagNode) node.getChild(0)).isSelfClosing()).isTrue();
  }

  @Test
  public void testDynamicTagName() {
    TemplateNode node = runPass("{let $t : 'div' /}<{$t}>content</{$t}>");
    assertThatSourceString(node).isEqualTo("{let $t : 'div' /}<{$t}>content</{$t}>");
    // NOTE: the print nodes don't end up in the AST due to how TagName works, this is probably a
    // bad idea in the long run.  We should probably make TagName be a node.
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  PRINT_NODE\n"
                + "");
  }

  @Test
  public void testDynamicAttributeValue() {
    TemplateNode node = runPass("{let $t : 'x' /}<div a={$t}>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div a={$t}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");
    // try alternate quotes
    node = runPass("{let $t : 'x' /}<div a=\"{$t}\">content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div a=\"{$t}\">content</div>");

    node = runPass("{let $t : 'x' /}<div a='{$t}'>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div a='{$t}'>content</div>");
  }

  @Test
  public void testDynamicAttribute() {
    TemplateNode node = runPass("{let $t : 'x' /}<div {$t}>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div {$t}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");

    // and with a value
    node = runPass("{let $t : 'x' /}<div {$t}=x>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div {$t}=x>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");

    node = runPass("{let $t : 'x' /}<div {$t}={$t}>content</div>");
    assertThatSourceString(node).isEqualTo("{let $t : 'x' /}<div {$t}={$t}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      PRINT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");

    node =
        runPass(
            "<div {call .name /}=x>content</div>{/template}" + "{template .name kind=\"text\"}foo");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    CALL_BASIC_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");
  }

  @Test
  public void testConditionalAttribute() {
    TemplateNode node = runPass("{let $t : 'x' /}<div {if $t}foo{else}bar{/if}>content</div>");
    assertThatSourceString(node)
        .isEqualTo("{let $t : 'x' /}<div{if $t} foo{else} bar{/if}>content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  IF_NODE\n"
                + "    IF_COND_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "    IF_ELSE_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");
  }

  @Test
  public void testConditionalAttributeValue() {
    TemplateNode node =
        runPass("{let $t : 'x' /}<div class=\"{if $t}foo{else}bar{/if}\">content</div>");
    assertThatSourceString(node)
        .isEqualTo("{let $t : 'x' /}<div class=\"{if $t}foo{else}bar{/if}\">content</div>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "LET_VALUE_NODE\n"
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    HTML_ATTRIBUTE_VALUE_NODE\n"
                + "      IF_NODE\n"
                + "        IF_COND_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "        IF_ELSE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "RAW_TEXT_NODE\n"
                + "HTML_CLOSE_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "");
  }

  // TODO(lukes): ideally these would all be implemented in the CompilerIntegrationTests but the
  // ContextualAutoescaper rejects these forms.  once we stop 'desuraging' prior to the autoescaper
  // we can move these tests over.

  @Test
  public void testConditionalContextMerging() {
    TemplateNode node = runPass("{@param p : ?}<div {if $p}foo=bar{else}baz{/if}>");
    assertThatSourceString(node).isEqualTo("<div{if $p} foo=bar{else} baz{/if}>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  IF_NODE\n"
                + "    IF_COND_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "        HTML_ATTRIBUTE_VALUE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "    IF_ELSE_NODE\n"
                + "      HTML_ATTRIBUTE_NODE\n"
                + "        RAW_TEXT_NODE\n"
                + "");
    node = runPass("{@param p : ?}<div {if $p}class=x{else}style=\"baz\"{/if}>");
    assertThatSourceString(node).isEqualTo("<div{if $p} class=x{else} style=\"baz\"{/if}>");

    node = runPass("{@param p : ?}<div {if $p}class='x'{else}style=\"baz\"{/if}>");
    assertThatSourceString(node).isEqualTo("<div{if $p} class='x'{else} style=\"baz\"{/if}>");
  }

  // Ideally, we wouldn't support this pattern since it adds a fair bit of complexity
  @Test
  public void testConditionalQuotedAttributeValues() {
    TemplateNode node = runPass("{@param p : ?}<div x={if $p}'foo'{else}'bar'{/if} {$p}>");
    assertThatSourceString(node).isEqualTo("<div x={if $p}'foo'{else}'bar'{/if} {$p}>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    IF_NODE\n"
                + "      IF_COND_NODE\n"
                + "        HTML_ATTRIBUTE_VALUE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "      IF_ELSE_NODE\n"
                + "        HTML_ATTRIBUTE_VALUE_NODE\n"
                + "          RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "");

    node =
        runPass(
            "{@param p : ?}{@param p2 : ?}<div x={if $p}{if $p2}'foo'{else}'bar'{/if}"
                + "{else}{if $p2}'foo'{else}'bar'{/if}{/if} {$p}>");
    assertThatSourceString(node)
        .isEqualTo(
            "<div x={if $p}{if $p2}'foo'{else}'bar'{/if}{else}{if $p2}'foo'{else}'bar'{/if}{/if}"
                + " {$p}>");
    assertThatASTString(node)
        .isEqualTo(
            ""
                + "HTML_OPEN_TAG_NODE\n"
                + "  RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    RAW_TEXT_NODE\n"
                + "    IF_NODE\n"
                + "      IF_COND_NODE\n"
                + "        IF_NODE\n"
                + "          IF_COND_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "          IF_ELSE_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "      IF_ELSE_NODE\n"
                + "        IF_NODE\n"
                + "          IF_COND_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "          IF_ELSE_NODE\n"
                + "            HTML_ATTRIBUTE_VALUE_NODE\n"
                + "              RAW_TEXT_NODE\n"
                + "  HTML_ATTRIBUTE_NODE\n"
                + "    PRINT_NODE\n"
                + "");
  }

  @Test
  public void testConditionalUnquotedAttributeValue() {
    TemplateNode node = runPass("{@param p : ?}<div class={if $p}x{else}y{/if}>");
    assertThatSourceString(node).isEqualTo("<div class={if $p}x{else}y{/if}>");
  }

  @Test
  public void testUnmatchedContextChangingCloseTagUnquotedAttributeValue() {
    // matched script is fine
    runPass("<script>xxx</script>");
    // unmatched closing div is fine.
    runPass("</div>");
    for (String tag : new String[] {"</script>", "</style>", "</title>", "</textarea>", "</xmp>"}) {
      ErrorReporter errorReporter = ErrorReporter.createForTest();
      runPass(tag, errorReporter);
      assertThat(Iterables.getOnlyElement(errorReporter.getErrors()).message())
          .named("error message for: %s", tag)
          .isEqualTo("Unexpected close tag for context-changing tag.");
    }
  }

  // regression test for a bug where we would drop rcdata content.
  @Test
  public void testRcDataTags() {
    assertThatSourceString(runPass("<script>xxx</script>"))
        .isEqualTo("<script{if $ij.csp_nonce} nonce=\"{$ij.csp_nonce}\"{/if}>xxx</script>");
  }

  @Test
  public void testBadTagName() {
    ErrorReporter errorReporter = ErrorReporter.createForTest();
    runPass("<3 >", errorReporter);
    assertThat(Iterables.getOnlyElement(errorReporter.getErrors()).message())
        .isEqualTo("Illegal tag name character.");
  }

  @Test
  public void testBadAttributeName() {
    ErrorReporter errorReporter = ErrorReporter.createForTest();
    runPass("<div foo-->", errorReporter);
    assertThat(Iterables.getOnlyElement(errorReporter.getErrors()).message())
        .isEqualTo("Illegal attribute name character.");
    errorReporter = ErrorReporter.createForTest();
    runPass("<div 0a>", errorReporter);
    assertThat(Iterables.getOnlyElement(errorReporter.getErrors()).message())
        .isEqualTo("Illegal attribute name character.");

    // these are fine, for weird reasons.  afaik, these characters aren't allowed by any defined
    // html attributes... but we'll allow them since some users are using them for weird reasons.
    // polymer uses _src and _style apparently
    runPass("<div _src='foo'>");
    runPass("<div $src='foo'>");
    runPass("<div $src_='foo'>");
  }

  @Test
  public void testHtmlCommentWithOnlyRawTextNode() {
    ErrorReporter errorReporter = ErrorReporter.createForTest();
    TemplateNode node;

    // The most common test case.
    node = runPass("<!--foo-->", errorReporter);
    assertThatASTString(node)
        .isEqualTo(Joiner.on('\n').join("HTML_COMMENT_NODE", "  RAW_TEXT_NODE", ""));
    assertThatSourceString(node).isEqualTo("<!--foo-->");

    // Empty comment is allowed.
    node = runPass("<!---->", errorReporter);
    assertThatASTString(node).isEqualTo(Joiner.on('\n').join("HTML_COMMENT_NODE", ""));
    assertThatSourceString(node).isEqualTo("<!---->");

    // White spaces should be preserved.
    node = runPass("<!-- foo -->", errorReporter);
    assertThatASTString(node)
        .isEqualTo(Joiner.on('\n').join("HTML_COMMENT_NODE", "  RAW_TEXT_NODE", ""));
    assertThatSourceString(node).isEqualTo("<!-- foo -->");

    // script tag within HTML comment should be treated as raw text.
    node = runPass("<!-- <script>alert(\"Hi\");</script> -->", errorReporter);
    assertThatASTString(node)
        .isEqualTo(Joiner.on('\n').join("HTML_COMMENT_NODE", "  RAW_TEXT_NODE", ""));
    assertThatSourceString(node).isEqualTo("<!-- <script>alert(\"Hi\");</script> -->");

    // This is fine since we never start a HTML comment, so it is treated as raw text.
    node = runPass("-->", errorReporter);
    assertThatASTString(node).isEqualTo(Joiner.on('\n').join("RAW_TEXT_NODE", ""));
    assertThatSourceString(node).isEqualTo("-->");
  }

  @Test
  public void testHtmlCommentWithPrintNode() {
    ErrorReporter errorReporter = ErrorReporter.createForTest();
    TemplateNode node;

    // Print node.
    node = runPass("<!--{$foo}-->", errorReporter);
    assertThatASTString(node)
        .isEqualTo(Joiner.on('\n').join("HTML_COMMENT_NODE", "  PRINT_NODE", ""));
    assertThatSourceString(node).isEqualTo("<!--{$foo}-->");

    // Mixed print node and raw text node.
    node = runPass("<!--{$foo}hello{$bar}-->", errorReporter);
    assertThatASTString(node)
        .isEqualTo(
            Joiner.on('\n')
                .join("HTML_COMMENT_NODE", "  PRINT_NODE", "  RAW_TEXT_NODE", "  PRINT_NODE", ""));
    assertThatSourceString(node).isEqualTo("<!--{$foo}hello{$bar}-->");
  }

  @Test
  public void testHtmlCommentWithControlFlow() {
    ErrorReporter errorReporter = ErrorReporter.createForTest();
    TemplateNode node;
    // Control flow structure should be preserved.
    node = runPass("<!-- {if $foo} foo {else} bar {/if} -->", errorReporter);
    assertThatASTString(node)
        .isEqualTo(
            Joiner.on('\n')
                .join(
                    "HTML_COMMENT_NODE",
                    "  RAW_TEXT_NODE",
                    "  IF_NODE",
                    "    IF_COND_NODE",
                    "      RAW_TEXT_NODE",
                    "    IF_ELSE_NODE",
                    "      RAW_TEXT_NODE",
                    "  RAW_TEXT_NODE",
                    ""));
    assertThatSourceString(node).isEqualTo("<!-- {if $foo} foo {else} bar {/if} -->");
  }

  @Test
  public void testBadHtmlComment() {
    ErrorReporter errorReporter = ErrorReporter.createForTest();
    // These are examples that we haven't closed the HTML comments.
    for (String text : new String[] {"<!--", "<!-- --", "<!--->"}) {
      errorReporter = ErrorReporter.createForTest();
      runPass(text, errorReporter);
      assertThat(Iterables.getOnlyElement(errorReporter.getErrors()).message())
          .named("error message for: %s", text)
          .isEqualTo(
              "template changes context from 'pcdata' to 'html comment'. "
                  + "Did you forget to close the html comment?");
    }
  }

  private static TemplateNode runPass(String input) {
    return runPass(input, ErrorReporter.exploding());
  }

  /** Parses the given input as a template content. */
  private static TemplateNode runPass(String input, ErrorReporter errorReporter) {
    String soyFile =
        Joiner.on('\n')
            .join("{namespace ns}", "", "{template .t stricthtml=\"false\"}", input, "{/template}");
    SoyFileNode node =
        SoyFileSetParserBuilder.forFileContents(soyFile)
            .desugarHtmlNodes(false)
            .errorReporter(errorReporter)
            .parse()
            .fileSet()
            .getChild(0);
    if (node != null) {
      return node.getChild(0);
    }
    return null;
  }

  private static StringSubject assertThatSourceString(TemplateNode node) {
    SoyFileNode parent = node.getParent().copy(new CopyState());
    new DesugarHtmlNodesPass().run(parent, new IncrementingIdGenerator());
    StringBuilder sb = new StringBuilder();
    parent.getChild(0).appendSourceStringForChildren(sb);
    return assertThat(sb.toString());
  }

  private static StringSubject assertThatASTString(TemplateNode node) {
    SoyFileNode parent = node.getParent().copy(new CopyState());
    new CombineConsecutiveRawTextNodesPass().run(parent);
    return assertThat(
        SoyTreeUtils.buildAstString(parent.getChild(0), 0, new StringBuilder()).toString());
  }
}
