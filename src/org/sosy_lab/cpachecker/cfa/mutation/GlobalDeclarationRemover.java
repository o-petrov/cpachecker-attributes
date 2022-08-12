// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CTypeDeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.util.Pair;
import org.sosy_lab.cpachecker.util.Triple;

class GlobalDeclarationRemover
    extends GenericDeltaDebuggingStrategy<
        Pair<ADeclaration, String>, Triple<Integer, ADeclaration, String>> {
  private DeclarationCollector dc = new DeclarationCollector();
  private boolean firstRun = true;

  public GlobalDeclarationRemover(LogManager pLogger) {
    super(pLogger, "global declarations");
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    boolean result;

    if (firstRun) {
      firstRun = false;
      dc.collectUsed(pCfa);
      return super.canMutate(pCfa);
    }

    // subseq runs
    result = super.canMutate(pCfa);
    if (!result) {
      reset();
      dc.collectUsed(pCfa);
      result = super.canMutate(pCfa);
    }
    return result;
  }

  @Override
  protected List<Pair<ADeclaration, String>> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    ImmutableSet<AFunctionDeclaration> functionDeclarations =
        FluentIterable.from(pCfa.getFunctions().values()).transform(CFANode::getFunction).toSet();
    List<Pair<ADeclaration, String>> result = new ArrayList<>();

    for (Pair<ADeclaration, String> gdPair : pCfa.getGlobalDeclarations()) {
      ADeclaration decl = gdPair.getFirst();
      if (getCauseObjects().contains(gdPair) || getRemainedSafeObjects().contains(gdPair)) {
        continue;
      }

      if (decl instanceof AFunctionDeclaration) {
        if (!functionDeclarations.contains(decl)) {
          result.add(gdPair);
        }

      } else if (decl instanceof AVariableDeclaration) {
        if (!dc.getUsedGlobalVariables().contains(decl)) {
          result.add(gdPair);
        }

      } else if (decl instanceof CTypeDeclaration) {
        if (!dc.getUsedTypes().contains(decl.getType())) {
          result.add(gdPair);
        }
      }
    }

    return result;
  }

  @Override
  protected Triple<Integer, ADeclaration, String> removeObject(
      FunctionCFAsWithMetadata pCfa, Pair<ADeclaration, String> pChosen) {
    int i = pCfa.getGlobalDeclarations().indexOf(pChosen);
    pCfa.getGlobalDeclarations().remove(pChosen);
    return Triple.of(i, pChosen.getFirst(), pChosen.getSecond());
  }

  @Override
  protected void restoreObject(
      FunctionCFAsWithMetadata pCfa, Triple<Integer, ADeclaration, String> pRemoved) {
    pCfa.getGlobalDeclarations()
        .add(pRemoved.getFirst(), Pair.of(pRemoved.getSecond(), pRemoved.getThird()));
  }
}
