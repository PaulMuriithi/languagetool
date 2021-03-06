/* LanguageTool, a natural language style checker
 * Copyright (C) 2013 Marcin Miłkowski (www.languagetool.org)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.rules.patterns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.languagetool.Language;

/**
 * Common pattern test routines (usable for Disambiguation rules as well).
 *
 * @author Marcin Miłkowski
 */
public class PatternTestTools {

    private static final Pattern PROBABLE_PATTERN = Pattern.compile(".*([^*]\\*|[.+?{}()|\\[\\]].*|\\\\d).*");

    // Polish POS tags use dots, so do not consider the presence of a dot
    // as indicating a probable regular expression.
    private static final Pattern PROBABLE_PATTERN_PL_POS = Pattern.compile(".*([^*]\\*|[+?{}()|\\[\\]].*|\\\\d).*");

    private static final Pattern CHAR_SET_PATTERN = Pattern.compile("(\\(\\?-i\\))?.*(?<!\\\\)\\[^?([^\\]]+)\\]");

    // TODO: probably this would be more useful for exceptions
    // instead of adding next methods to PatternRule
    // we can probably validate using XSD and specify regexes straight there
    public static void warnIfRegexpSyntaxNotKosher(final List<Element> elements,
            final String ruleId,final String ruleSubId, final Language lang) {
        int i = 0;
        for (final Element element : elements) {
          i++;

          if (element.isReferenceElement()) {
              continue;
          }

          // Check whether token value is consistent with regexp="..."
          warnIfElementNotKosher(
            element.getString(),
            element.isRegularExpression(),
            element.isCaseSensitive(),
            element.getNegation(),
            element.isInflected(),
            false,  // not a POS
            lang, ruleId + ":" + ruleSubId,
            i);

          // Check postag="..." is consistent with postag_regexp="..."
          warnIfElementNotKosher(
            element.getPOStag() == null ? "" : element.getPOStag(),
            element.isPOStagRegularExpression(),
            element.isCaseSensitive(),
            element.getPOSNegation(),
            false,
            true,   // a POS.
            lang, ruleId + ":" + ruleSubId + " (POS tag)",
            i);

          final List<Element> exceptionElements = new ArrayList<>();
          if (element.getExceptionList() != null) {
            for (final Element exception: element.getExceptionList()) {
              // Detect useless exception or missing skip="...". I.e. things like this:
              // <token postag="..."><exception scope="next">foo</exception</token>
              if (exception.hasNextException() && element.getSkipNext() == 0) {
                System.err.println("The " + lang.toString() + " rule: "
                    + ruleId + ":" + ruleSubId
                    + " (exception in token [" + i + "])"
                    + " has no skip=\"...\" and yet contains scope=\"next\""
                    + " so the exception never applies."
                    + " Did you forget skip=\"...\"?");
              }

              // Detect exception that can't possibly be matched. Example:
              // <token>foo<exception>bar</exception></token>
              // This could be improved to detect for example useless
              // exception in :
              // <token regexp="yes">foo|bar<exception>xxx</exception></token>
              if (!element.isRegularExpression()
                && !element.getString().isEmpty()
                && !element.getNegation()
                && !element.isInflected()
                && element.getSkipNext() == 0
                && element.getPOStag() == null
                && exception.getPOStag() == null
                && element.isCaseSensitive() == exception.isCaseSensitive()) {
                System.err.println("The " + lang.toString() + " rule: "
                    + ruleId + ":" + ruleSubId
                    + " exception in token [" + i + "] seems useless."
                    + " Did you forget skip=\"...\" or scope=\"previous\"?");
              }

              // Check whether exception value is consistent with regexp="..."
              // Don't check string "." since it is sometimes used as a regexp
              // and sometimes used as non regexp.
              if (!exception.getString().equals(".")) {
                warnIfElementNotKosher(
                  exception.getString(),
                  exception.isRegularExpression(),
                  exception.isCaseSensitive(),
                  exception.getNegation(),
                  exception.isInflected(),
                  false,  // not a POS
                  lang,
                  ruleId + ":" + ruleSubId+ " (exception in token [" + i + "])",
                  i);
              }
              // Check postag="..." of exception is consistent with postag_regexp="..."
              warnIfElementNotKosher(
                exception.getPOStag() == null ? "" : exception.getPOStag(),
                exception.isPOStagRegularExpression(),
                exception.isCaseSensitive(),
                exception.getPOSNegation(),
                false,
                true,  // a POS
                lang,
                ruleId + ":" + ruleSubId + " (exception in POS tag of token [" + i + "])",
                i);

              // Search for duplicate exceptions (which are useless).
              // Since there are 2 nested loops on the list of exceptions,
              // this has thus a O(n^2) complexity, where n is the number
              // of exceptions in a token. But n is small and it is also
              // for testing only so that's OK.
              for (final Element otherException: exceptionElements) {
                if (equalException(exception, otherException)) {
                  System.err.println("The " + lang.toString() + " rule: "
                      + ruleId + ":" + ruleSubId
                      + " in token [" + i + "]"
                      + " contains duplicate exceptions with"
                      + " string=[" + exception.getString() + "]"
                      + " POS tag=[" + exception.getPOStag() + "]"
                      + " negate=[" + exception.getNegation() + "]"
                      + " POS negate=[" + exception.getPOSNegation() + "]");
                  break;
                }
              }
              exceptionElements.add(exception);
            }
          }
        }

    }

    /**
     * Predicate to check whether two exceptions are identical or whether
     * one exception always implies the other.
     *
     * Example #1, useless identical exceptions:
     * <exception>xx</exception><exception>xx</exception>
     *
     * Example #2, first exception implies the second exception:
     * <exception>xx</exception><exception postag="A">xx</exception>
     */
    private static boolean equalException(final Element exception1,
                                          final Element exception2)
    {
      String string1 = exception1.getString() == null ? "" : exception1.getString();
      String string2 = exception2.getString() == null ? "" : exception2.getString();
      if (!exception1.isCaseSensitive() || !exception2.isCaseSensitive()) {
        // String comparison is done case insensitive if one or both strings
        // are case insensitive, because the case insensitive one would imply
        // the case sensitive one.
        string1 = string1.toLowerCase();
        string2 = string2.toLowerCase();
      }
      if (!string1.isEmpty() && !string2.isEmpty()) {
        if (!string1.equals(string2)) {
          return false;
        }
      }

      final String posTag1 = exception1.getPOStag() == null ? "" : exception1.getPOStag();
      final String posTag2 = exception2.getPOStag() == null ? "" : exception2.getPOStag();
      if (!posTag1.isEmpty() && !posTag2.isEmpty()) {
        if (!posTag1.equals(posTag2)) {
          return false;
        }
      }

      if ( string1.isEmpty() != string2.isEmpty()
        && posTag1.isEmpty() != posTag2.isEmpty()) {
        return false;
      }

      // We should not need to check for:
      // - isCaseSensitive() since an exception without isCaseSensitive
      //   imply the one with isCaseSensitive.
      // - isInflected() since an exception with inflected="yes"
      //   implies the one without inflected="yes" if they have
      //   identical strings.
      //   without inflected="yes".
      // - isRegularExpression() since a given string is either
      //   a regexp or not.
      return exception1.getNegation() == exception2.getNegation()
          && exception1.getPOSNegation() == exception2.getPOSNegation()
          && exception1.hasNextException() == exception2.hasNextException()
          && exception1.hasPreviousException() == exception2.hasPreviousException();
    }

    private static void warnIfElementNotKosher(
            final String stringValue,
            final boolean isRegularExpression,
            final boolean isCaseSensitive,
            final boolean isNegated,
            final boolean isInflected,
            final boolean isPos,
            final Language lang,
            final String ruleId,
            final int tokenIndex) {

          // Use a different regexp to check for probably regexp in Polish POS tags
          // since Polish uses dot '.' in POS tags. So a dot does not indicate that
          // it's a probable regexp for Polish POS tags.
          final Pattern regexPattern = (isPos && lang.getShortName().equals("pl"))
                                     ? PROBABLE_PATTERN_PL_POS // Polish POS tag.
                                     : PROBABLE_PATTERN;       // something else than Polish POS tag.

          if (!isRegularExpression && stringValue.length() > 1
              && regexPattern.matcher(stringValue).find()) {
            System.err.println("The " + lang.toString() + " rule: "
                + ruleId + ", token [" + tokenIndex + "], contains " + "\"" + stringValue
                + "\" that is not marked as regular expression but probably is one.");
          }

          if (isRegularExpression && stringValue.isEmpty()) {
            System.err.println("The " + lang.toString() + " rule: "
                + ruleId + ", token [" + tokenIndex + "], contains an empty string " + "\""
                + stringValue + "\" that is marked as regular expression.");
          } else if (isRegularExpression && stringValue.length() > 1
                     && !regexPattern.matcher(stringValue).find()) {
            System.err.println("The " + lang.toString() + " rule: "
                + ruleId + ", token [" + tokenIndex + "], contains " + "\"" + stringValue
                + "\" that is marked as regular expression but probably is not one.");
          }

          if (isNegated && stringValue.isEmpty()) {
            System.err.println("The " + lang.toString() + " rule: "
                + ruleId + ", token [" + tokenIndex + "], marked as negated but is "
                + "empty so the negation is useless. Did you mix up "
                + "negate=\"yes\" and negate_pos=\"yes\"?");
          }
          if (isInflected && stringValue.isEmpty()) {
            System.err.println("The " + lang.toString() + " rule: "
                + ruleId + ", token [" + tokenIndex + "], contains " + "\"" + stringValue
                + "\" that is marked as inflected but is empty, so the attribute is redundant.");
          }
          if (isRegularExpression && ".*".equals(stringValue)) {
            System.err.println("The " + lang.toString() + " rule: "
                + ruleId + ", token [" + tokenIndex + "], marked as regular expression contains "
                + "regular expression \".*\" which is useless: "
                + "(use an empty string without regexp=\"yes\" such as <token/>)");
          }

          if (isRegularExpression) {
            final Matcher matcher = CHAR_SET_PATTERN.matcher(stringValue);
            if (matcher.find()) {
              // Remove things like \p{Punct} which are irrelevant here.
              final String s = matcher.group(2).replaceAll("\\\\p\\{[^}]*\\}", "");
              // case sensitive if pattern contains (?-i).
              if (s.indexOf('|') >= 0) {
                System.err.println("The " + lang.toString() + " rule: "
                   + ruleId + ", token [" + tokenIndex + "], contains | (pipe) in "
                   + " regexp bracket expression [" + matcher.group(2)
                   + "] which is unlikely to be correct.");
              }

              /* Disabled case insensitive check for now: it gives several errors
               * in German which are minor and debatable whether it adds value.
              final boolean caseSensitive = matcher.group(1) != null || isCaseSensitive;
              if (!caseSensitive) {
                s = s.toLowerCase();
              }
              */
              final char[] sorted = s.toCharArray();
              // Sort characters in string, so finding duplicate characters can be done by
              // looking for identical adjacent characters.
              java.util.Arrays.sort(sorted);
              for (int i = 1; i < sorted.length; ++i) {
                final char c = sorted[i];
                if ("&\\-|".indexOf(c) < 0 && sorted[i - 1] == c) {
                  System.err.println("The " + lang.toString() + " rule: "
                     + ruleId + ", token [" + tokenIndex + "], contains "
                     + " regexp part [" + matcher.group(2)
                     + "] which contains duplicated char [" + c + "].");
                  break;
                }
              }
            }
            if (stringValue.contains("|")) {
              if ( stringValue.indexOf("||") >= 0
                || stringValue.charAt(0) == '|'
                || stringValue.charAt(stringValue.length() - 1) == '|') {
                // Empty disjunctions in regular expression are most likely not intended.
                System.err.println("The " + lang.toString() + " rule: "
                    + ruleId + ", token [" + tokenIndex + "], contains empty "
                    + "disjunction | within " + "\"" + stringValue + "\".");
              }
              final String[] groups = stringValue.split("\\)");
              for (final String group : groups) {
                final String[] alt = group.split("\\|");
                final Set<String> partSet = new HashSet<>();
                final Set<String> partSetNoCase = new HashSet<>();
                for (String part : alt) {
                  final String partNoCase = isCaseSensitive ? part : part.toLowerCase();
                  if (partSetNoCase.contains(partNoCase)) {
                    if (partSet.contains(part)) {
                      // Duplicate disjunction parts "foo|foo".
                      System.err.println("The " + lang.toString() + " rule: "
                          + ruleId + ", token [" + tokenIndex + "], contains "
                          + "duplicated disjunction part ("
                          + part + ") within " + "\"" + stringValue + "\".");
                    } else {
                      // Duplicate disjunction parts "Foo|foo" since element ignores case.
                      System.err.println("The " + lang.toString() + " rule: "
                          + ruleId + ", token [" + tokenIndex + "], contains duplicated "
                          + "non case sensitive disjunction part ("
                          + part + ") within " + "\"" + stringValue + "\". Did you "
                          + "forget case_sensitive=\"yes\"?");
                    }
                  }
                  partSetNoCase.add(partNoCase);
                  partSet.add(part);
                }
              }
            }
          }

        }


}
