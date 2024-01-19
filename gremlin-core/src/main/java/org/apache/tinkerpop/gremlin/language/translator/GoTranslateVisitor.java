/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.language.translator;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.tinkerpop.gremlin.language.grammar.GremlinParser;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoTranslateVisitor extends AbstractTranslateVisitor {
    private final static String GO_PACKAGE_NAME = "gremlingo.";

    public GoTranslateVisitor() {
        super("g");
    }

    public GoTranslateVisitor(final String graphTraversalSourceName) {
        super(graphTraversalSourceName);
    }

    @Override
    public Void visitDateLiteral(final GremlinParser.DateLiteralContext ctx) {
        // child at 2 is the date argument to datetime() and comes enclosed in quotes
        final String dtString = ctx.getChild(2).getText();
        final Date dt = DatetimeHelper.parse(removeFirstAndLastCharacters(dtString));
        sb.append("time.UnixMilli(" + dt.getTime() + ")");
        return null;
    }

    @Override
    public Void visitInfLiteral(final GremlinParser.InfLiteralContext ctx) {
        if (ctx.SignedInfLiteral().getText().equals("-Infinity"))
            sb.append("math.Inf(-11)");
        else
            sb.append("math.Inf(1)");
        return null;
    }

    @Override
    public Void visitIntegerLiteral(final GremlinParser.IntegerLiteralContext ctx) {
        String integerLiteral = ctx.getText().toLowerCase();

        // check suffix
        int lastCharIndex = integerLiteral.length() - 1;
        char lastChar = integerLiteral.charAt(lastCharIndex);

        // if the last character is not alphabetic then try to interpret the right type and append the suffix
        if (Character.isAlphabetic(lastChar))
            sb.append(integerLiteral, 0, lastCharIndex);
        else
            sb.append(integerLiteral);
        return null;
    }

    @Override
    public Void visitFloatLiteral(final GremlinParser.FloatLiteralContext ctx) {
        String floatLiteral = ctx.getText().toLowerCase();

        // check suffix
        int lastCharIndex = floatLiteral.length() - 1;
        char lastChar = floatLiteral.charAt(lastCharIndex);

        // if the last character is not alphabetic then try to interpret the right type and append the suffix
        if (Character.isAlphabetic(lastChar))
            sb.append(floatLiteral, 0, lastCharIndex);
        else
            sb.append(floatLiteral);
        return null;
    }

    @Override
    public Void visitGenericLiteralRange(final GremlinParser.GenericLiteralRangeContext ctx) {
        throw new TranslatorException("Go does not support range literals");
    }

    @Override
    public Void visitGenericLiteralCollection(final GremlinParser.GenericLiteralCollectionContext ctx) {
        sb.append("[]interface{}{");
        for (int i = 0; i < ctx.genericLiteral().size(); i++) {
            final GremlinParser.GenericLiteralContext genericLiteralContext = ctx.genericLiteral(i);
            visit(genericLiteralContext);
            if (i < ctx.genericLiteral().size() - 1)
                sb.append(", ");
        }
        sb.append("}");
        return null;
    }

    @Override
    public Void visitGenericLiteralMap(final GremlinParser.GenericLiteralMapContext ctx) {
        sb.append("map[interface{}]interface{}{");
        for (int i = 0; i < ctx.mapEntry().size(); i++) {
            final GremlinParser.MapEntryContext mapEntryContext = ctx.mapEntry(i);
            visit(mapEntryContext);
            if (i < ctx.mapEntry().size() - 1)
                sb.append(", ");
        }
        sb.append(" }");
        return null;
    }

    @Override
    public Void visitMapEntry(final GremlinParser.MapEntryContext ctx) {
        // if it is a terminal node then it has to be processed as a string for Java but otherwise it can
        // just be handled as a generic literal
        if (ctx.getChild(0) instanceof TerminalNode) {
            handleStringLiteralText(ctx.getChild(0).getText());
        }  else {
            visit(ctx.getChild(0));
        }
        sb.append(": ");
        visit(ctx.getChild(2)); // value
        return null;
    }

    @Override
    public Void visitStringNullableLiteral(GremlinParser.StringNullableLiteralContext ctx) {
        // remove the first and last character (single or double quotes) but only if it is not null
        if (ctx.getText().equals("null")) {
            sb.append("nil");
        } else {
            final String text = removeFirstAndLastCharacters(ctx.getText());
            handleStringLiteralText(text);
        }
        return null;
    }

    @Override
    public Void visitNanLiteral(final GremlinParser.NanLiteralContext ctx) {
        sb.append("math.NaN()");
        return null;
    }

    @Override
    public Void visitNullLiteral(final GremlinParser.NullLiteralContext ctx) {
        sb.append("nil");
        return null;
    }

    @Override
    public Void visitStructureVertex(final GremlinParser.StructureVertexContext ctx) {
        sb.append(GO_PACKAGE_NAME).append("Vertex{Element{");
        visit(ctx.getChild(3)); // id
        sb.append(", ");
        visit(ctx.getChild(5)); // label
        sb.append("}}");
        return null;
    }

    @Override
    public Void visitTraversalStrategy(final GremlinParser.TraversalStrategyContext ctx) {
        if (ctx.getChildCount() == 1)
            sb.append(GO_PACKAGE_NAME).append(ctx.getText()).append("()");
        else {
            sb.append(GO_PACKAGE_NAME).append(ctx.getChild(1).getText()).append("(");
            sb.append(GO_PACKAGE_NAME + ctx.getChild(1).getText() + "Config{");

            // get a list of all the arguments to the strategy - i.e. anything not a terminal node
            final List<ParseTree> strategyArgs = ctx.children.stream()
                    .filter(c -> !(c instanceof TerminalNode))
                    .collect(java.util.stream.Collectors.toList());

            for (int i = 0; i < strategyArgs.size(); i++) {
                visit(strategyArgs.get(i));
                if (i < strategyArgs.size() - 1)
                    sb.append(", ");
            }
            sb.append("})");
        }

        return null;
    }

    @Override
    public Void visitTraversalCardinality(final GremlinParser.TraversalCardinalityContext ctx) {
        // handle the enum style of cardinality if there is one child, otherwise it's the function call style
        if (ctx.getChildCount() == 1)
            appendExplicitNaming(ctx.getText(), VertexProperty.Cardinality.class.getSimpleName());
        else {
            String txt = ctx.getChild(0).getText();
            if (txt.startsWith("Cardinality.")) {
                txt = txt.replaceFirst("Cardinality.", "");
            }
            appendExplicitNaming(txt, "CardinalityValue");
            appendStepOpen();
            visit(ctx.getChild(2));
            appendStepClose();
        }

        return null;
    }

    protected void visitP(final ParserRuleContext ctx, final Class<?> clazzOfP, final String methodName) {
        sb.append(GO_PACKAGE_NAME);
        super.visitP(ctx, clazzOfP, methodName);
    }

    @Override
    protected String processGremlinSymbol(final String step) {
        return SymbolHelper.toGo(step);
    }

    @Override
    protected Void appendStrategyArguments(final ParseTree ctx) {
        sb.append(SymbolHelper.toGo(ctx.getChild(0).getText())).append(": ");
        visit(ctx.getChild(2));
        return null;
    }

    @Override
    protected void appendExplicitNaming(final String txt, final String prefix) {
        sb.append(GO_PACKAGE_NAME);
        super.appendExplicitNaming(txt, prefix);
    }

    @Override
    protected void appendAnonymousSpawn() {
        sb.append(GO_PACKAGE_NAME).append("T__.");
    }

    static final class SymbolHelper {

        private final static Map<String, String> TO_GO_MAP = new HashMap<>();
        private final static Map<String, String> FROM_GO_MAP = new HashMap<>();

        static {
            TO_GO_MAP.put("OUT", "Out");
            TO_GO_MAP.put("IN", "In");
            TO_GO_MAP.put("BOTH", "Both");
            TO_GO_MAP.put("__", GO_PACKAGE_NAME + "T__");
            TO_GO_MAP.forEach((k, v) -> FROM_GO_MAP.put(v, k));
        }

        private SymbolHelper() {
            // static methods only, do not instantiate
        }

        public static String toGo(final String symbol) {
            return TO_GO_MAP.getOrDefault(symbol, StringUtils.capitalize(symbol));
        }

        public static String toJava(final String symbol) {
            return FROM_GO_MAP.getOrDefault(symbol, StringUtils.uncapitalize(symbol));
        }

    }
}
