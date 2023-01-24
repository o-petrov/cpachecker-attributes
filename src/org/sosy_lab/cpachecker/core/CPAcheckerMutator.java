// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
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
import org.sosy_lab.common.log.LoggingOptions;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.common.time.Timer;
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

@Options(prefix = "cfaMutation")
public class CPAcheckerMutator extends CPAchecker {

  @Option(
      secure = true,
      name = "rollbacksInRowCheck",
      description =
          "If a mutation round is unsuccessfull (i.e. sought-for bug does not occur), "
              + "the mutation is rollbacked. If this count of rollbacks occur in row, "
              + "check that rollbacked CFA produces the sought-for bug.\n"
              + "If set to 0, do not check any rollbacks.\n"
              + "If set to 1, check that bug occurs after every rollback.\n"
              + "If set to 2, check every other one that occurs immediately after "
              + "another one, so if there occur 5 rollbacks in a row, 2nd and 4th "
              + "will be checked. And so on.")
  private int checkAfterRollbacks = 5;

  @Option(
      secure = true,
      name = "walltimeLimit.factor",
      description =
          "Sometimes analysis run can be unpredictably long. To run many rounds successfully, "
              + "CFA mutator needs to setup its own time limit for each round. "
              + "It is walltime for original analysis run multiplied by factor, "
              + "plus additional bias. By default it is original run * 2.0 + 20s.")
  private double timelimitFactor = 2.0;

  @Option(
      secure = true,
      name = "walltimeLimit.add",
      description =
          "Sometimes analysis run can be unpredictably long. To run many rounds successfully, "
              + "CFA mutator needs to setup its own time limit for each round. "
              + "It is walltime for original analysis run multiplied by factor, "
              + "plus additional bias. By default it is original run * 2.0 + 20s.")
  private TimeSpan timelimitBias = TimeSpan.ofSeconds(20);

  private interface ResourceLimitsFactory {
    public List<ResourceLimit> create();
  }

  private ResourceLimitsFactory limitsFactory = () -> ImmutableList.of();
  private final ImmutableList<ResourceLimit> globalLimits;
  private final LoggingOptions logOptions;

  public CPAcheckerMutator(
      Configuration pConfiguration,
      LogManager pLogManager,
      ShutdownManager pShutdownManager,
      ResourceLimitChecker pLimits,
      LoggingOptions pLogOptions)
      throws InvalidConfigurationException {
    super(pConfiguration, pLogManager, pShutdownManager);
    config.inject(this, CPAcheckerMutator.class);
    globalLimits = ImmutableList.copyOf(pLimits.getResourceLimits());
    logOptions = pLogOptions;
  }

  @Override
  public CPAcheckerResult run(List<String> programDenotation) {
    checkArgument(!programDenotation.isEmpty());
    logger.logf(
        Level.INFO, "%s / CFA mutation (%s) started", getVersion(config), getJavaInformation());
    MainCFAMutationStatistics totalStats = new MainCFAMutationStatistics();
    CFAMutator cfaMutator = null;
    AnalysisResult lastResult = null;

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
      AnalysisResult originalResult =
          lastResult = analysisRound(getCfa(), logger, totalStats.originalTime);
      if (originalResult.getVerdict() == Result.TRUE) {
        // TRUE verdicts are assumed to be correct,
        // and there is no simple way to differ one from
        // another, so do not deal with wrong one.
        logger.log(
            Level.SEVERE,
            "Analysis finished correctly. Can not minimize CFA for given TRUE verdict.");
        return originalResult.asMutatorResult(Result.NOT_YET_STARTED, cfaMutator, totalStats);

      } else if (originalResult.getVerdict() == Result.FALSE) {
        // FALSE verdicts are assumed to be correct, unless given original incorrect one.
        logger.log(
            Level.WARNING,
            "Analysis finished correctly. Mutated CFA may be meaningless for given FALSE verdict.");
      }

      cfaMutator.setup();
      // set walltime limit for every single round
      TimeSpan scaledWalltime =
          TimeSpan.ofMillis(
              (long) (totalStats.originalTime.getConsumedTime().asMillis() * timelimitFactor));
      limitsFactory =
          () ->
              ImmutableList.of(
                  WalltimeLimit.fromNowOn(
                      TimeSpan.sum(TimeSpan.ofMillis(1), timelimitBias, scaledWalltime)));
      logger.log(
          Level.INFO,
          "Using",
          Joiner.on(", ").join(Iterables.transform(limitsFactory.create(), ResourceLimit::getName)),
          "for the following rounds");

      String shutdownReason = shouldShutdown();
      if (shutdownReason != null) {
        logger.logf(
            Level.INFO,
            "CFA mutation interrupted before it started to mutate the CFA (%s)",
            shutdownReason);
        return originalResult.asMutatorResult(Result.NOT_YET_STARTED, cfaMutator, totalStats);
      }

      // CFAMutator stores needed info from #parse,
      // so no need to pass CFA as argument in next calls

      int rollbacksInRow = 0;
      for (int round = 1; cfaMutator.canMutate(); round++) {
        logger.log(Level.INFO, "Mutation round", round);

        CFA mutated = cfaMutator.mutate();
        LogManager roundLogger = cfaMutator.createRoundLogger(logOptions);
        lastResult = analysisRound(mutated, roundLogger, totalStats.afterMutations);
        // TODO export intermediate results
        // XXX it is incorrect to save intermediate stats, as reached is updated?

        DDResultOfARun ddRunResult = lastResult.toDDResult(originalResult);
        logger.log(Level.INFO, ddRunResult);
        CFA rollbacked = cfaMutator.setResult(ddRunResult);

        shutdownReason = shouldShutdown();
        if (shutdownReason != null) {
          logger.logf(
              Level.INFO,
              "CFA mutation interrupted after %s. analysis round (%s)",
              round,
              shutdownReason);
          return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
        }

        if (rollbacked == null || checkAfterRollbacks == 0) {
          // no need to check
          rollbacksInRow = 0;

        } else if (++rollbacksInRow % checkAfterRollbacks == 0) {
          logger.log(
              Level.INFO, "Running analysis after", rollbacksInRow, "mutation rollback in row");

          LogManager checkLogger = cfaMutator.createRoundLogger(logOptions);
          lastResult = analysisRound(rollbacked, checkLogger, totalStats.afterRollbacks);
          if (!shutdownNotifier.shouldShutdown()) {
            // if stop not because of global shutdown, it must be FAIL
            Verify.verify(lastResult.toDDResult(originalResult) == DDResultOfARun.FAIL);
          }

          shutdownReason = shouldShutdown();
          if (shutdownReason != null) {
            logger.logf(
                Level.INFO,
                "CFA mutation interrupted after rollback after %s. analysis round (%s)",
                round,
                shutdownReason);
            return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
          }
        }
      }

      logger.log(Level.INFO, "CFA mutation ended, as no more minimizatins can be found");
      return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);

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

    }

    if (lastResult == null) {
      return new CPAcheckerResult(Result.NOT_YET_STARTED, "", null, null, null);
    }
    return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
  }

  // check for requested shutdown and whether it is enough time for next analysis run
  private @Nullable String shouldShutdown() {
    if (shutdownNotifier.shouldShutdown()) {
      return shutdownNotifier.getReason();
    }

    List<ResourceLimit> nextRunLimits = limitsFactory.create();
    for (ResourceLimit localLimit : nextRunLimits) {
      Class<? extends ResourceLimit> cls = localLimit.getClass();
      long localTimeout = localLimit.nanoSecondsToNextCheck(localLimit.getCurrentValue());
      for (ResourceLimit globalLimit : globalLimits) {
        if (cls.isInstance(globalLimit) && globalLimit.isExceeded(localTimeout)) {
          return globalLimit.getName() + " will exceed during next analysis run";
        }
      }
    }

    return null;
  }

  // run analysis, but for already stored CFA, and catch its errors
  private AnalysisResult analysisRound(CFA pCfa, LogManager pLogger, StatTimer pTimer)
      throws InvalidConfigurationException {
    Timer timer = new Timer();
    timer.start();

    Throwable t = null;
    ShutdownNotifier parentNotifier = shutdownNotifier;
    ShutdownManager roundShutdownManager = ShutdownManager.createWithParent(parentNotifier);
    ResourceLimitChecker limits =
        new ResourceLimitChecker(roundShutdownManager, limitsFactory.create());
    if (limits.getResourceLimits().isEmpty()) {
      pLogger.log(Level.INFO, "No resource limits for round specified");
    } else {
      pLogger.log(
          Level.INFO,
          "Using",
          Joiner.on(", ")
              .join(Iterables.transform(limits.getResourceLimits(), ResourceLimit::getName)));
    }
    limits.start();
    pTimer.start();

    CPAchecker cpachecker = new CPAchecker(config, pLogger, roundShutdownManager);

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
      pTimer.stop();
      timer.stop();
    }

    CPAcheckerResult cur = cpachecker.produceResult();
    if (t == null) {
      logger.log(Level.INFO, cur.getResultString());
    } else {
      // exception was already logged
    }
    pLogger.log(Level.INFO, "Used", timer.getSumTime());

    return new AnalysisResult(cur, t);
  }

  // keep last result and thrown error
  private static class AnalysisResult {
    private final CPAcheckerResult result;
    private final @Nullable Throwable thrown;

    public AnalysisResult(CPAcheckerResult pResult, @Nullable Throwable pThrown) {
      result = pResult;
      thrown = pThrown;
    }

    public CPAcheckerResult asMutatorResult(
        Result pResult, CFAMutator pCfaMutator, MainCFAMutationStatistics pTotalStats) {
      if (pTotalStats != null) {
        if (pCfaMutator != null) {
          pCfaMutator.collectStatistics(pTotalStats.subStats);
        }
        pTotalStats.lastMainStats = getStats();
      }
      String desc = getVerdict() == Result.FALSE ? result.getTargetDescription() : "";
      return new CPAcheckerResult(pResult, desc, result.getReached(), result.getCfa(), pTotalStats);
    }

    public Result getVerdict() {
      return result.getResult();
    }

    public MainCPAStatistics getStats() {
      return (MainCPAStatistics) result.getStatistics();
    }

    public DDResultOfARun toDDResult(AnalysisResult pOriginal) {
      switch (getVerdict()) {
        case TRUE:
          // assume TRUE is always correct
          return DDResultOfARun.PASS;

        case FALSE:
          // FALSE verdicts are assumed to be correct, unless given original incorrect one.
          if (pOriginal.getVerdict() != Result.FALSE) {
            return DDResultOfARun.PASS;
          }
          // XXX FALSE are UNRESOLVED unless same description
          return pOriginal.getVerdict() == Result.FALSE
                  && result.getTargetDescription().equals(pOriginal.result.getTargetDescription())
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

    @Override
    public int hashCode() {
      return result.hashCode() * 31 + (thrown == null ? 0 : thrown.hashCode());
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
