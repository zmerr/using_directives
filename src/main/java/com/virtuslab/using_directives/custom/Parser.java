package com.virtuslab.using_directives.custom;

import static com.virtuslab.using_directives.custom.utils.TokenUtils.*;

import com.virtuslab.using_directives.Context;
import com.virtuslab.using_directives.custom.model.UsingDirectiveSyntax;
import com.virtuslab.using_directives.custom.utils.Source;
import com.virtuslab.using_directives.custom.utils.ast.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class Parser {

  private Source source;

  private final Context context;

  public Parser(Source source, Context context) {
    this.context = context;
    this.source = source;
    this.in = new Scanner(source, 0, context);
  }

  public Context getContext() {
    return context;
  }

  Scanner in;

  /* Combinators */

  private void error(String msg, int offset) {
    context.getReporter().error(source.getPositionFromOffset(offset), msg);
  }

  private void error(String msg) {
    error(msg, offset(in.td.offset));
  }

  private int offset(int off) {
    return source.translateOffset(off);
  }

  public boolean isStatSep() {
    return in.td.isNewLine() || in.td.token == Tokens.SEMI;
  }

  public void accept(Tokens token) {
    if (in.td.token == token) {
      in.nextToken();
    } else {
      error(String.format("Expected token %s but found %s", token.str, in.td.token.str));
    }
  }

  public <T> T enclosed(Tokens token, Supplier<T> callback) {
    accept(token);
    try {
      return callback.get();
    } finally {
      accept(tokenFromInt(token.ordinal() + 1));
    }
  }

  public <T> T inBracesOrIndented(Supplier<T> callback) {
    switch (in.td.token) {
      case INDENT:
        return enclosed(Tokens.INDENT, callback);
      case LBRACE:
        return enclosed(Tokens.LBRACE, callback);
      default:
        error(String.format("Expected indent or braces but found %s", in.td.token.str));
        return callback.get();
    }
  }

  public void possibleTemplateStart() {
    in.observeColonEOL();
    if (in.td.token == Tokens.COLONEOL) {
      if (in.lookahead().token == Tokens.END) {
        in.td.token = Tokens.NEWLINE;
      } else {
        in.nextToken();
        if (in.td.token != Tokens.INDENT && in.td.token != Tokens.LBRACE) {
          error(String.format("Expected indent or braces but found %s", in.td.token.str));
        }
      }
    } else {
      newLineOptWhenFollowedBy(Tokens.LBRACE);
    }
  }

  public void newLineOptWhenFollowedBy(Tokens token) {
    if (in.td.token == Tokens.NEWLINE && in.next.token == token) {
      in.nextToken();
    }
  }

  /* */

  public UsingDefs parse() {
    UsingDefs t = usingDirectives();
    return t;
  }

  UsingDefs usingDirectives() {
    ArrayList<UsingDef> usingTrees = new ArrayList<>();
    int codeOffset = 0;
    UsingDef ud = usingDirective();
    int offset = offset(in.td.offset);
    while (ud != null) {
      usingTrees.add(ud);
      codeOffset = offset(in.td.lastOffset);
      if (in.td.token == Tokens.NEWLINE || in.td.token == Tokens.NEWLINES) in.nextToken();
      ud = usingDirective();
    }
    return new UsingDefs(
        usingTrees, codeOffset, offset(in.td.offset), source.getPositionFromOffset(offset));
  }

  UsingDef usingDirective() {
    if (isValidUsingDirectiveStart(in.td.token, context.getSettings())) {
      int offset = offset(in.td.offset);

      UsingDirectiveSyntax syntax = UsingDirectiveSyntax.Using;
      if (in.td.token == Tokens.ATUSING) syntax = UsingDirectiveSyntax.AtUsing;
      else if (in.td.token == Tokens.ATREQUIRE) syntax = UsingDirectiveSyntax.AtRequire;
      else if (in.td.token == Tokens.REQUIRE) syntax = UsingDirectiveSyntax.Require;

      in.nextToken();
      return new UsingDef(settings(), syntax, source.getPositionFromOffset(offset));
    }

    return null;
  }

  private List<SettingDef> parseSettings() {
    if (isStatSep()) {
      if (in.lookahead().token == Tokens.IDENTIFIER) {
        in.nextToken();
        List<SettingDef> settings = new ArrayList<>();
        settings.add(setting());
        settings.addAll(parseSettings());
        return settings;
      } else {
        return new ArrayList<>();
      }
    } else if (in.td.token == Tokens.IDENTIFIER) {
      List<SettingDef> settings = new ArrayList<>();
      settings.add(setting());
      settings.addAll(parseSettings());
      return settings;
    } else {
      return new ArrayList<>();
    }
  }

  SettingDefs settings() {
    possibleTemplateStart();
    ArrayList<SettingDef> settings = new ArrayList<>();
    int offset = offset(in.td.offset);
    if (in.td.token == Tokens.IDENTIFIER) {
      settings.add(setting());
    } else {
      settings.addAll(inBracesOrIndented(() -> this.parseSettings()));
    }
    return new SettingDefs(settings, source.getPositionFromOffset(offset));
  }

  SettingDef setting() {
    int offset = offset(in.td.offset);
    String key = key();
    SettingDefOrUsingValue value = valueOrSetting();
    return new SettingDef(key, value, source.getPositionFromOffset(offset));
  }

  String key() {
    if (in.td.token == Tokens.IDENTIFIER) {
      String key = in.td.name;
      in.nextToken();
      if (in.td.token == Tokens.DOT) {
        in.nextToken();
        return key + "." + key();
      } else {
        return key;
      }
    }
    error(String.format("Expected identifier but found %s", in.td.token.str));
    return null;
  }

  SettingDefOrUsingValue valueOrSetting() {
    if (literalTokens.contains(in.td.token)
        || (in.td.token == Tokens.IDENTIFIER && in.td.name.equals("-"))
        || (in.td.token != Tokens.IDENTIFIER
            && in.td.token != Tokens.LBRACE
            && in.td.token != Tokens.COLON)) {
      UsingValue v = value();
      String scope = scope();
      if (scope != null) {
        if (v instanceof UsingPrimitive) {
          ((UsingPrimitive) v).setScope(scope);
        } else {
          ((UsingValues) v).getValues().forEach(p -> p.setScope(scope));
        }
      }
      return v;
    } else {
      return settings();
    }
  }

  UsingValue value() {
    int offset = offset(in.td.offset);
    UsingPrimitive p = primitive();

    if (in.td.token == Tokens.COMMA) {
      in.nextToken();
      UsingValue rest = value();
      if (rest instanceof UsingPrimitive) {
        ArrayList<UsingPrimitive> res = new ArrayList<>();
        res.add(p);
        res.add((UsingPrimitive) rest);
        return new UsingValues(res, source.getPositionFromOffset(offset));
      } else {
        ((UsingValues) rest).getValues().add(0, p);
        return rest;
      }
    } else {
      return p;
    }
  }

  String scope() {
    if (in.td.token == Tokens.IDENTIFIER && in.td.name.equals("in")) {
      in.nextToken();
      if (in.td.token == Tokens.STRINGLIT) {
        String scope = in.td.strVal;
        in.nextToken();
        return scope;
      } else {
        error(String.format("Expected token STRINGLIT but found %s", in.td.token.str));
        return null;
      }
    } else {
      return null;
    }
  }

  private final List<Tokens> numericTokens =
      Arrays.asList(
          Tokens.INTLIT,
          Tokens.DECILIT,
          Tokens.EXPOLIT,
          Tokens.LONGLIT,
          Tokens.FLOATLIT,
          Tokens.DOUBLELIT);

  UsingPrimitive primitive() {
    int offset = offset(in.td.offset);
    UsingPrimitive res = null;
    if (in.td.token == Tokens.STRINGLIT) {
      res = new StringLiteral(in.td.strVal, source.getPositionFromOffset(offset));
      in.nextToken();
    } else if (in.td.token == Tokens.IDENTIFIER && in.td.name.equals("-")) {
      in.nextToken();
      if (numericTokens.contains(in.td.token)) {
        res = new NumericLiteral("-" + in.td.strVal, source.getPositionFromOffset(offset));
        in.nextToken();
      } else {
        String solution = "Wrapping identifier in quotes usually solves the problem.";

        error(
            String.format(
                "Expected primitive value: string, numeric or boolean but found identifier: '%s'. %s",
                in.td.token.str, solution));
      }
    } else if (numericTokens.contains(in.td.token)) {
      res = new NumericLiteral(in.td.strVal, source.getPositionFromOffset(offset));
      in.nextToken();
    } else if (in.td.token == Tokens.TRUE) {
      res = new BooleanLiteral(true, source.getPositionFromOffset(offset));
      in.nextToken();
    } else if (in.td.token == Tokens.FALSE) {
      res = new BooleanLiteral(false, source.getPositionFromOffset(offset));
      in.nextToken();
    } else {
      res = new BooleanLiteral(true, source.getPositionFromOffset(offset));
    }
    return res;
  }
}
