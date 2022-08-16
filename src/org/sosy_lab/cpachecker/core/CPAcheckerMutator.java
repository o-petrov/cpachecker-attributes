// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutator;
import org.sosy_lab.cpachecker.cfa.mutation.DDResultOfARun;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.resources.ResourceLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.resources.WalltimeLimit;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;
import org.sosy_lab.cpachecker.util.statistics.StatisticsUtils;

@Options
public class CPAcheckerMutator extends CPAchecker {

  @Option(
      secure = true,
      name = "cfaMutation.checkAfterRollbacks",
      description =
          "If a mutation round is unsuccessfull (i.e. hids the bug), the mutation "
              + "is rollbacked. Check that rollbacked CFA produces the sought-for "
              + "bug. (Increases amount of analysis runs.)")
  private boolean checkAfterRollbacks = true;

  private ResourceLimitsFactory limitsFactory = () -> ImmutableList.of();

  public CPAcheckerMutator(
      Configuration pConfiguration, LogManager pLogManager, ShutdownManager pShutdownManager)
      throws InvalidConfigurationException {
    super(pConfiguration, pLogManager, pShutdownManager);
    config.inject(this, CPAcheckerMutator.class);
  }

  @Override
  public CPAcheckerResult run(List<String> programDenotation) {
    checkArgument(!programDenotation.isEmpty());
    logger.logf(
        Level.INFO, "%s / CFA mutation (%s) started", getVersion(config), getJavaInformation());
    MainCFAMutationStatistics totalStats = new MainCFAMutationStatistics();
    CFAMutator cfaMutator = null;

    try {
      if (serializedCfaFile != null) {
        throw new InvalidConfigurationException(
            "CFA mutation needs source files to be parsed into CFA. "
                + "Either specify 'cfaMutation=true' or specify "
                + "loading CFA with 'analysis.serializedCfaFile'.");
      }

      // XXX other way?
      @SuppressWarnings("deprecation")
      String withAcsl = config.getProperty("parser.collectACSLAnnotations");
      if ("true".equals(withAcsl)) {
        throw new InvalidConfigurationException(
            "CFA mutation can not handle ACSL annotations. Do not specify "
                + "'cfaMutation=true' and 'parser.collectACSLAnnotations=true' simultaneously");
      }

      cfaMutator = new CFAMutator(config, logger, shutdownNotifier);
      parse(cfaMutator, programDenotation);
      totalStats.setCFACreatorStatistics(cfaMutator.getStatistics());
      if (getCfa() == null) {
        // invalid input files
        return produceResult();
      }

      // To use Delta Debugging algorithm we need to define PASS, FAIL, and UNRESOLVED outcome of
      // a 'test'. Here a test run is analysis run, and FAIL is same exception as in original
      // input program. Assume verdicts TRUE and FALSE are correct, let it be PASS then.
      // Any other exceptions, and verdicts NOT_YET_STARTED and UNKNOWN are UNRESOLVED.
      totalStats.originalTime.start();
      AnalysisResult originalResult = analysisRound(getCfa());
      totalStats.originalTime.stop();
      if (originalResult.verdict == Result.TRUE) {
        // TRUE verdicts are assumed to be correct,
        // and there is no simple way to differ one from
        // another, so do not deal with wrong one.
        logger.log(
            Level.SEVERE,
            "Analysis finished correctly. Can not minimize CFA for given TRUE verdict.");
        cfaMutator.collectStatistics(totalStats.subStats);
        totalStats.lastMainStats = originalResult.getStats();
        return produceResult(Result.NOT_YET_STARTED, totalStats);
      } else if (originalResult.verdict == Result.FALSE) {
        // FALSE verdicts are assumed to be correct, unless given original incorrect one.
        logger.log(
            Level.WARNING,
            "Analysis finished correctly. Mutated CFA may be meaningless for given FALSE verdict.");
      }

      cfaMutator.setup();
      // set walltime limit for every single round
      limitsFactory =
          () ->
              ImmutableList.of(
                  WalltimeLimit.fromNowOn(
                      TimeSpan.sum(
                          TimeSpan.ofSeconds(10),
                          totalStats.originalTime.getConsumedTime().multiply(2))));

      // CFAMutator stores needed info from #parse,
      // so no need to pass CFA as argument in next calls
      for (int round = 1; cfaMutator.canMutate(); round++) {

        if (shutdownNotifier.shouldShutdown()) {
          logger.logf(
              Level.INFO,
              "CFA mutation interrupted between rounds (%s)",
              shutdownNotifier.getReason());
          totalStats.lastMainStats = getStats();
          return produceResult(Result.DONE, totalStats);
        }

        logger.log(Level.INFO, "Mutation round", round);

        CFA mutated = cfaMutator.mutate();
        TimeSpan before = totalStats.afterMutations.getConsumedTime();
        totalStats.afterMutations.start();
        AnalysisResult newResult = analysisRound(mutated);
        totalStats.afterMutations.stop();
        logger.log(
            Level.INFO,
            "Used",
            TimeSpan.difference(totalStats.afterMutations.getConsumedTime(), before));
        // TODO export intermediate results
        // XXX it is incorrect to save intermediate stats, as reached is updated?

        DDResultOfARun ddRunResult = newResult.toDDResult(originalResult);
        logger.log(Level.INFO, ddRunResult);
        CFA rollbacked = cfaMutator.setResult(ddRunResult);
        if (rollbacked != null) {

          if (shutdownNotifier.shouldShutdown()) {
            logger.logf(
                Level.INFO,
                "CFA mutation interrupted after rollback (%s)",
                shutdownNotifier.getReason());
            totalStats.lastMainStats = getStats();
            return produceResult(Result.DONE, totalStats);
          }

          if (checkAfterRollbacks) {
            logger.log(Level.INFO, "Running analysis after mutation rollback");
            totalStats.afterRollbacks.start();
            newResult = analysisRound(rollbacked);
            totalStats.afterRollbacks.stop();
            Verify.verify(newResult.toDDResult(originalResult) == DDResultOfARun.FAIL);
          }
        }
      }

      logger.log(Level.INFO, "CFA mutation ended, as no more minimizatins can be found");
      totalStats.lastMainStats = getStats();
      return produceResult(Result.DONE, totalStats);

    } catch (InvalidConfigurationException e) {
      logger.logUserException(Level.SEVERE, e, "Invalid configuration");
      // XXX move to #analysisRound?
      // but for first round should catch here?

    } catch (InterruptedException e) {
      // CPAchecker must exit because it was asked to
      // we return normally instead of propagating the exception
      // so we can return the partial result we have so far
      logger.logUserException(Level.WARNING, e, "CFA mutation interrupted");

    } catch (ParserException e) {
      logger.logUserException(Level.SEVERE, e, "Parser error");
      // XXX is it possible?
      // TODO export previous CFA then?

    } finally {
      if (cfaMutator != null) {
        cfaMutator.collectStatistics(totalStats.subStats);
      }
    }

    return produceResult(Result.NOT_YET_STARTED, totalStats);
  }

  private interface ResourceLimitsFactory {
    public List<ResourceLimit> create();
  }

  // run analysis, but for already stored CFA, and catch its errors
  private AnalysisResult analysisRound(CFA pCfa) throws InvalidConfigurationException {
    Throwable t = null;
    ShutdownNotifier parentNotifier = shutdownNotifier;
    ShutdownManager roundShutdownManager = ShutdownManager.createWithParent(parentNotifier);
    ResourceLimitChecker limits =
        new ResourceLimitChecker(roundShutdownManager, limitsFactory.create());
    if (limits.getResourceLimits().isEmpty()) {
      logger.log(Level.INFO, "No resource limits for round specified");
    } else {
      logger.log(
          Level.INFO,
          "Using",
          Iterables.transform(limits.getResourceLimits(), ResourceLimit::getName));
    }
    limits.start();

    // TODO log to file
    CPAchecker cpachecker =
        new CPAchecker(config, LogManager.createNullLogManager(), roundShutdownManager);

    try {
      cpachecker.setupMainStats();
      cpachecker.setCfa(pCfa);
      try {
        cpachecker.setupAnalysis();
      } finally {
        cpachecker.stopMainStatsCreationTimer();
      }
      cpachecker.runAnalysis();

    } catch (InterruptedException e) {
      assert cpachecker.shutdownNotifier.shouldShutdown();
      // this round was too long
      t = e;
      logger.logf(
          Level.WARNING,
          "Analysis round interrupted (%s)",
          cpachecker.shutdownNotifier.getReason());

    } catch (AssertionError
        | IllegalArgumentException
        | IllegalStateException
        | IndexOutOfBoundsException
        | ClassCastException
        | NullPointerException
        | NoSuchElementException
        | VerifyException
        | CPAException e) {
      // Expect exceptions as bugs of CPAchecker, and remember them to reproduce on a smaller CFA.
      // TODO which types exactly
      if (e.getStackTrace().length == 0) {
        // too many same exceptions were thrown, it's JVM optimization
        logger.log(Level.WARNING, "Recurring error:", e.getClass(), "(no stack trace)");
      } else {
        logger.logUserException(Level.WARNING, e, null);
      }
      t = e;

    } finally {
      limits.cancel();
      cpachecker.closeCPAsIfPossible();
    }

    CPAcheckerResult cur = cpachecker.produceResult();
    if (t == null) {
      logger.log(Level.INFO, cur.getResultString());
    } else {
      // exception was already logged
    }

    return AnalysisResult.from(cur, t, cpachecker.getStats());
  }

  // no need to check for reached, cfa, or stats change,
  // so keep only verdict and description, plus thrown error
  private static class AnalysisResult {
    private final Result verdict;
    private final String description;
    private final @Nullable Throwable thrown;
    private final MainCPAStatistics stats;

    static AnalysisResult from(CPAcheckerResult r, @Nullable Throwable t, MainCPAStatistics s) {
      String d = r.getResult() == Result.FALSE ? r.getTargetDescription() : "";
      return new AnalysisResult(r.getResult(), d, t, s);
    }

    private AnalysisResult(
        Result pResult, String pTarget, @Nullable Throwable pThrown, MainCPAStatistics pStats) {
      checkArgument(
          pResult == Result.FALSE || pTarget.isEmpty(),
          "Target description must be empty when result is not FALSE. %s: %s",
          pResult,
          pTarget);
      checkArgument(
          pResult == Result.NOT_YET_STARTED || pResult == Result.UNKNOWN || pThrown == null,
          "Exception or error must be null when result is not NOT_YET_STARTED or UNKNOWN. %s: %s (%s)",
          pResult,
          pThrown == null ? "Null" : pThrown.getClass(),
          pThrown == null ? "null" : pThrown.getMessage());
      verdict = pResult;
      description = pTarget;
      thrown = pThrown;
      stats = pStats;
    }

    public DDResultOfARun toDDResult(AnalysisResult pOriginal) {
      switch (verdict) {
        case TRUE:
          // assume TRUE is always correct
          return DDResultOfARun.PASS;

        case FALSE:
          // FALSE verdicts are assumed to be correct, unless given original incorrect one.
          if (pOriginal.verdict != Result.FALSE) {
            return DDResultOfARun.PASS;
          }
          // XXX FALSE are UNRESOLVED unless same description
          return description.equals(pOriginal.description)
              ? DDResultOfARun.FAIL
              : DDResultOfARun.UNRESOLVED;

        case NOT_YET_STARTED:
        case UNKNOWN:
          if (thrown == null || pOriginal.thrown == null) {
            // there was no exception
            return DDResultOfARun.UNRESOLVED;
          }
          // if equal, then same fail, else some other problem, i.e. unresolved
          return pOriginal.thrown.getClass().equals(thrown.getClass())
                  && (thrown.getStackTrace().length == 0 // optimized by JVM
                      || pOriginal.thrown.getStackTrace()[0].equals(thrown.getStackTrace()[0]))
              ? DDResultOfARun.FAIL
              : DDResultOfARun.UNRESOLVED;

        default:
          throw new AssertionError();
      }
    }

    public MainCPAStatistics getStats() {
      return stats;
    }

    @Override
    public int hashCode() {
      return verdict.hashCode() * 31
          + description.hashCode() * 37
          + (thrown == null ? 0 : thrown.hashCode());
    }
  }

  class MainCFAMutationStatistics implements Statistics {
    private MainCPAStatistics lastMainStats = null;
    private Statistics cfaCreatorStats = null;
    private List<Statistics> subStats = new ArrayList<>();

    final StatTimer originalTime = new StatTimer("time for original analysis run");
    final StatTimerWithMoreOutput afterMutations =
        new StatTimerWithMoreOutput("total time for analysis after mutations");
    final StatTimerWithMoreOutput afterRollbacks =
        new StatTimerWithMoreOutput("total time for analysis after rollbacks");

    @Override
    public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {

      if (lastMainStats != null) {
        pOut.println("Last analysis statistics");
        pOut.println("========================");
        StatisticsUtils.printStatistics(lastMainStats, pOut, logger, pResult, pReached);
        StatisticsUtils.writeOutputFiles(lastMainStats, logger, pResult, pReached);
        pOut.println();
      }

      if (cfaCreatorStats != null) {
        pOut.println("CFA creation statistics");
        pOut.println("=======================");
        StatisticsUtils.printStatistics(cfaCreatorStats, pOut, logger, pResult, pReached);
        StatisticsUtils.writeOutputFiles(cfaCreatorStats, logger, pResult, pReached);
        pOut.println();
      }

      if (originalTime.getUpdateCount() > 0) {
        pOut.println("CFA mutation statistics");
        pOut.println("=======================");
        pOut.println("Time for analysis");
        pOut.println("-----------------");
        put(pOut, 1, originalTime);
        put(pOut, 1, afterMutations);
        if (afterRollbacks.getUpdateCount() > 0) {
          put(pOut, 1, afterRollbacks);
        }
      }

      subStats.forEach(
          s -> {
            StatisticsUtils.printStatistics(s, pOut, logger, pResult, pReached);
            StatisticsUtils.writeOutputFiles(s, logger, pResult, pReached);
          });
    }

    public void setCFACreatorStatistics(Statistics pStats) {
      cfaCreatorStats = pStats;
    }

    @Override
    public @Nullable String getName() {
      return "CFA mutation";
    }

    @Override
    public void writeOutputFiles(Result pResult, UnmodifiableReachedSet pReached) {
      if (lastMainStats != null) {
        lastMainStats.writeOutputFiles(pResult, pReached);
      }

      subStats.forEach(s -> StatisticsUtils.writeOutputFiles(s, logger, pResult, pReached));
    }
  }
}
