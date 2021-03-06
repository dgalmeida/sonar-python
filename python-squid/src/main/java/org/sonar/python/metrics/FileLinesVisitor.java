/*
 * SonarQube Python Plugin
 * Copyright (C) 2011-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.python.metrics;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AstNodeType;
import com.sonar.sslr.api.GenericTokenType;
import com.sonar.sslr.api.Token;
import com.sonar.sslr.api.Trivia;
import java.util.Set;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.python.DocstringExtractor;
import org.sonar.python.PythonVisitor;
import org.sonar.python.TokenLocation;
import org.sonar.python.api.PythonTokenType;

/**
 * Visitor that computes {@link CoreMetrics#NCLOC_DATA_KEY} and {@link CoreMetrics#COMMENT_LINES_DATA_KEY} metrics used by the DevCockpit.
 */
public class FileLinesVisitor extends PythonVisitor {

  private static final PythonCommentAnalyser COMMENT_ANALYSER = new PythonCommentAnalyser();

  private boolean seenFirstToken;

  private final boolean ignoreHeaderComments;

  private Set<Integer> noSonar = Sets.newHashSet();
  private Set<Integer> linesOfCode = Sets.newHashSet();
  private Set<Integer> linesOfComments = Sets.newHashSet();
  private Set<Integer> linesOfDocstring = Sets.newHashSet();

  public FileLinesVisitor(boolean ignoreHeaderComments) {
    this.ignoreHeaderComments = ignoreHeaderComments;
  }

  @Override
  public Set<AstNodeType> subscribedKinds() {
    return DocstringExtractor.DOCUMENTABLE_NODE_TYPES;
  }

  @Override
  public void visitFile(AstNode astNode) {
    noSonar.clear();
    linesOfCode.clear();
    linesOfComments.clear();
    linesOfDocstring.clear();
    seenFirstToken = false;
  }

  @Override
  public void visitNode(AstNode astNode) {
    Token docstringToken = DocstringExtractor.extractDocstring(astNode);
    if (docstringToken != null) {
      TokenLocation location = new TokenLocation(docstringToken);
      for (int line = location.startLine(); line <= location.endLine(); line++) {
        linesOfDocstring.add(line);
      }
    }
  }

  /**
   * Gets the lines of codes and lines of comments (with character #).
   * Does not get the lines of docstrings.
   */
  @Override
  public void visitToken(Token token) {
    if (token.getType().equals(GenericTokenType.EOF)) {
      return;
    }

    if (!token.getType().equals(PythonTokenType.DEDENT) && !token.getType().equals(PythonTokenType.INDENT) && !token.getType().equals(PythonTokenType.NEWLINE)) {
      //  Handle all the lines of the token
      String[] tokenLines = token.getValue().split("\n", -1);
      for (int line = token.getLine(); line < token.getLine() + tokenLines.length; line++) {
        linesOfCode.add(line);
      }
    }

    if (ignoreHeaderComments && !seenFirstToken) {
      seenFirstToken = true;
      return;
    }

    for (Trivia trivia : token.getTrivia()) {
      if (trivia.isComment()) {
        visitComment(trivia);
      }
    }
  }

  public void visitComment(Trivia trivia) {
    String[] commentLines = COMMENT_ANALYSER.getContents(trivia.getToken().getOriginalValue())
      .split("(\r)?\n|\r", -1);
    int line = trivia.getToken().getLine();

    for (String commentLine : commentLines) {
      if (commentLine.contains("NOSONAR")) {
        linesOfComments.remove(line);
        noSonar.add(line);
      } else if (!COMMENT_ANALYSER.isBlank(commentLine) && !noSonar.contains(line)) {
        linesOfComments.add(line);
      }
      line++;
    }
  }

  @Override
  public void leaveFile(AstNode astNode) {
    // account for the docstring lines
    for (Integer line : linesOfDocstring) {
      linesOfCode.remove(line);
      linesOfComments.add(line);
    }
  }

  public Set<Integer> getLinesWithNoSonar() {
    return ImmutableSet.copyOf(noSonar);
  }

  public Set<Integer> getLinesOfCode() {
    return ImmutableSet.copyOf(linesOfCode);
  }

  public Set<Integer> getLinesOfComments() {
    return ImmutableSet.copyOf(linesOfComments);
  }

  private static class PythonCommentAnalyser {

    public boolean isBlank(String line) {
      for (int i = 0; i < line.length(); i++) {
        if (Character.isLetterOrDigit(line.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    public String getContents(String comment) {
      // Comment always starts with "#"
      return comment.substring(comment.indexOf('#'));
    }
  }
}
