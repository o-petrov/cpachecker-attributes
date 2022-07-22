// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import javax.annotation.Nullable;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.exceptions.ParserException;

/**
 * Mutates the CFA before next analysis run, mainly to minimize and simplify CFA. Operates on {@link
 * FunctionCFAsWithMetadata}. All processings in {@link CFACreator#createCFA} are applied after this to
 * get proper CFA for analysis run.
 */
public class CFAMutator extends CFACreator {
  /** local CFA of functions before processing */
  private FunctionCFAsWithMetadata localCfa = null;
  /** Strategy that decides how to change the CFA and implements this change */
  private final CFAMutationStrategy strategy;

  public CFAMutator(Configuration pConfig, LogManager pLogger, ShutdownNotifier pShutdownNotifier)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pShutdownNotifier);
    strategy = new CFAMutationStrategy();
  }

  public boolean canMutate() {
    return strategy.canMutate(localCfa);
  }

  /** Apply some mutation to the CFA */
  public CFA mutate() throws InterruptedException, InvalidConfigurationException, ParserException {
    localCfa = strategy.mutate(localCfa);
    return createCFA(localCfa, localCfa.getMainFunction());
  }

  /**
   * Undo last mutation.
   *
   * @param needsFullyConstructed Whether properly constructed CFA is needed as result. If false,
   *     only changes to local CFA will be reverted, and interprocedural graph will not be
   *     constructed, so null is returned. If true, ready CFA will be returned.
   * @return Either null, or ready CFA.
   */
  public @Nullable CFA rollback(boolean needsFullyConstructed)
      throws InterruptedException, InvalidConfigurationException, ParserException {
    localCfa = strategy.mutate(localCfa);
    if (needsFullyConstructed) {
      return createCFA(localCfa, localCfa.getMainFunction());
    } else {
      return null;
    }
  }
}
