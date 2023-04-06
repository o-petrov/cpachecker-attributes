// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.TreeMultiset;
import com.google.common.io.MoreFiles;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import javax.management.JMException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.ConfigurationBuilder;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.configuration.converters.FileTypeConverter;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.LoggingOptions;
import org.sosy_lab.common.log.TimestampedLogFormatter;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CParser;
import org.sosy_lab.cpachecker.cfa.CParser.ParserOptions;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutator;
import org.sosy_lab.cpachecker.core.CPAcheckerMutator.AnalysisResult;
import org.sosy_lab.cpachecker.core.CPAcheckerMutator.AnalysisRun;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleCheckAlgorithm.CounterexampleCheckerType;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleChecker;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.DelegatingCheckerWithRestoredFunctions;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.resources.ProcessCpuTime;
import org.sosy_lab.cpachecker.util.resources.ProcessCpuTimeLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.resources.ThreadCpuTimeLimit;
import org.sosy_lab.cpachecker.util.resources.WalltimeLimit;

@Options(prefix = "cfaMutation")
final class CFAMutationManager {

  @Option(
      secure = true,
      name = "logFile.level",
      description =
          "Create log file for every round with this logging level.\n"
              + "Use main file logging level if that is lower.")
  private Level fileLogLevel = Level.FINE;

  @Option(
      secure = true,
      name = "logFile",
      description =
          "Create log file for every round with this logging level.\n"
              + "Use main file logging level if that is lower.")
  private String roundLogFile = "this-round.log";

  @Option(
      secure = true,
      name = "roundStatFile",
      description = "File to dump statistics of current round to")
  private String roundStatFile = "this-round-stats.txt";

  @Option(
      secure = true,
      name = "rankedNodesFile",
      description =
          "File to write how frequent CFA node is among resulted reached set states' locations.")
  private String rankedNodesFile = "this-round-ranked-nodes.txt";

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
              + "another one, so if 5 rollbacks occur in a row, 2nd and 4th "
              + "will be checked. And so on.")
  private int checkAfterRollbacks = 5;

  @Option(
      secure = true,
      name = "timelimit.factor",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard-caps time for every run (200s by default), and also soft-caps "
              + "time for the runs after original by multiplying used time by the factor and "
              + "adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original (first) "
              + "run gets hard-cap time limit, all others get the lower between the two.\n"
              + "For every global timelimit specified for whole mutation process, a timelimit for "
              + "every type of present global timelimit will be produced. E.g., if both CPU and "
              + "walltime limits specified, every analysis round gets two timelimits (of CPU and "
              + "walltime type) with same time span.")
  private double timelimitFactor = 2.0;

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 0)
  @Option(
      secure = true,
      name = "timelimit.add",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard-caps time for every run (200s by default), and also soft-caps "
              + "time for the runs after original by multiplying used time by the factor and "
              + "adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original (first) "
              + "run gets hard-cap time limit, all others get the lower between the two.\n"
              + "For every global timelimit specified for whole mutation process, a timelimit for "
              + "every type of present global timelimit will be produced. E.g., if both CPU and "
              + "walltime limits specified, every analysis round gets two timelimits (of CPU and "
              + "walltime type) with same time span.")
  private TimeSpan timelimitBias = TimeSpan.ofSeconds(5);

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 10)
  @Option(
      secure = true,
      name = "timelimit.hardcap",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard-caps time for every run (200s by default), and also soft-caps "
              + "time for the runs after original by multiplying used time by the factor and "
              + "adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original (first) "
              + "run gets hard-cap time limit, all others get the lower between the two.\n"
              + "For every global timelimit specified for whole mutation process, a timelimit for "
              + "every type of present global timelimit will be produced. E.g., if both CPU and "
              + "walltime limits specified, every analysis round gets two timelimits (of CPU and "
              + "walltime type) with same time span.")
  private TimeSpan hardcap = TimeSpan.ofSeconds(200);

  @Option(
      secure = true,
      name = "cex.checker",
      description =
          "Which model checker to use for verifying counterexample when an error is found using "
              + "CFA mutations. Currently CBMC, CPAchecker with same or different config, or the "
              + "concrete execution checker can be used.")
  private CounterexampleCheckerType checkerType = CounterexampleCheckerType.CBMC;

  @Option(
      secure = true,
      name = "cex.checker.config",
      description =
          "If CPAchecker is used as CEX-checker, it uses this configuration file or "
              + "defaults to same configuration that found CEX.")
  private @Nullable String cpacheckerConfigFile;

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 10)
  @Option(
      secure = true,
      name = "cex.timelimit",
      description =
          "Limit time for countrexample feasibility check. This option is used only if CFA "
              + "mutations are used to find a feasible error. As with analysis timelimit, "
              + "timelimits with respect to global time limits' types are produced.")
  private TimeSpan timeForCex = TimeSpan.ofSeconds(60);

  private TimeSpan originalRun;

  private final ImmutableList<ResourceLimit> globalLimits;

  private final Configuration config;
  private final LogManager logger;
  private final ShutdownManager shutdownManager;

  private Specification originalSpec;
  private final CFAMutator cfaMutator;

  private ResourceLimitChecker currentLimits;
  private final Timer currentTimer = new Timer();
  private LogManager currentLogger;

  public CFAMutationManager(
      Configuration pConfig,
      LogManager pLogger,
      ShutdownManager pShutdownManager,
      ResourceLimitChecker pLimits,
      CFAMutator pCfaMutator)
      throws InvalidConfigurationException {

    config = Preconditions.checkNotNull(pConfig);
    logger = Preconditions.checkNotNull(pLogger);
    shutdownManager = Preconditions.checkNotNull(pShutdownManager);
    cfaMutator = Preconditions.checkNotNull(pCfaMutator);

    config.inject(this, CFAMutationManager.class);

    ParserOptions parserOptions = CParser.Factory.getOptions(pConfig);
    if (parserOptions.shouldCollectACSLAnnotations()) {
      throw new InvalidConfigurationException(
          "CFA mutation can not handle ACSL annotations. Do not specify "
              + "'cfaMutation=true' and 'parser.collectACSLAnnotations=true' simultaneously");
    }

    ImmutableList.Builder<ResourceLimit> relevantLimits = ImmutableList.builder();
    for (ResourceLimit limit : pLimits.getResourceLimits()) {
      if (limit instanceof ProcessCpuTimeLimit) {
        try {
          ProcessCpuTime.read();
          relevantLimits.add(limit);
        } catch (JMException e) {
          logger.logDebugException(e, "filtering out irrelevant limits for CFA mutation");
        }

      } else if (limit instanceof ThreadCpuTimeLimit) {
        relevantLimits.add(limit);

      } else if (limit instanceof WalltimeLimit) {
        relevantLimits.add(limit);

      } else {
        logger.log(
            Level.WARNING,
            "Unknown type of resource limit",
            limit,
            "will be ignored while constructing resource limits",
            "for analysis rounds and feasibility check");
      }
    }

    globalLimits = relevantLimits.build();
    if (globalLimits.isEmpty()) {
      logger.log(
          Level.WARNING,
          "No resource limits will be used for analysis rounds and feasibility check");
    }
  }

  int getCheckAfterRollbacks() {
    return checkAfterRollbacks;
  }

  private ImmutableList<ResourceLimit> produceLimitsFromNowOn(TimeSpan pTime) {
    ImmutableList.Builder<ResourceLimit> result = ImmutableList.builder();
    for (ResourceLimit globalLimit : globalLimits) {
      if (globalLimit instanceof ProcessCpuTimeLimit) {
        try {
          result.add(ProcessCpuTimeLimit.fromNowOn(pTime));
        } catch (JMException e) {
          throw new AssertionError(e);
        }

      } else if (globalLimit instanceof ThreadCpuTimeLimit) {
        result.add(ThreadCpuTimeLimit.fromNowOn(pTime, Thread.currentThread()));

      } else if (globalLimit instanceof WalltimeLimit) {
        result.add(WalltimeLimit.fromNowOn(pTime));

      } else {
        throw new AssertionError(
            "unexpeted " + globalLimit.getClass() + " class of time limit " + globalLimit);
      }
    }
    return result.build();
  }

  private ImmutableList<ResourceLimit> getLimitsForAnalysis() {
    if (originalRun == null) {
      return produceLimitsFromNowOn(hardcap);
    }

    // choose lower of hard and soft caps
    TimeSpan lowerCap = hardcap;
    double softcap = originalRun.asMillis() * timelimitFactor + timelimitBias.asMillis();
    if (softcap < hardcap.asMillis()) {
      lowerCap = TimeSpan.ofMillis((long) softcap);
    }

    return produceLimitsFromNowOn(lowerCap);
  }

  public void setOriginalTime(TimeSpan pConsumedTime) {
    Preconditions.checkState(originalRun == null);
    originalRun = pConsumedTime;

    logger.log(
        Level.INFO,
        "Using",
        Joiner.on(", ").join(Iterables.transform(getLimitsForAnalysis(), ResourceLimit::getName)),
        "for the following rounds");
  }

  public void setSpecification(Specification pSpec) {
    Preconditions.checkState(originalSpec == null);
    originalSpec = Preconditions.checkNotNull(pSpec);
  }

  public boolean exceedsLimitsForAnalysis(String pDescription) {
    return shouldShutdown(
        getLimitsForAnalysis(), "will exceed during next analysis run", pDescription);
  }

  public boolean exceedsLimitsForFeasibility() {
    return shouldShutdown(
        produceLimitsFromNowOn(timeForCex),
        "will exceed during next feasibility check",
        "CFA mutation does not have enough time to check last error");
  }

  private boolean shouldShutdown(
      ImmutableList<ResourceLimit> pLocalLimits, String pWillExceed, String pDescription) {
    if (shutdownManager.getNotifier().shouldShutdown()) {
      logger.log(Level.INFO, () -> pDescription + ": " + shutdownManager.getNotifier().getReason());
      return true;
    }

    for (int i = 0; i < pLocalLimits.size(); i++) {
      ResourceLimit localLimit = pLocalLimits.get(i);
      ResourceLimit globalLimit = globalLimits.get(i);
      assert globalLimit.getClass().isInstance(localLimit) : "limits dont match";

      long localTimeout =
          localLimit.nanoSecondsToNextCheck(localLimit.getCurrentValue())
              + TimeSpan.ofSeconds(1).asNanos(); // plus 1s for my code before and after analysis

      if (globalLimit.isExceeded(globalLimit.getCurrentValue() + localTimeout)) {
        logger.log(
            Level.INFO, () -> pDescription + ": " + globalLimit.getName() + ' ' + pWillExceed);
        return true;
      }
    }

    return false;
  }

  public Configuration createRoundConfig(AnalysisRun pRun) throws InvalidConfigurationException {
    ConfigurationBuilder builder =
        Configuration.builder().copyFrom(config).setOption("log.file", roundLogFile.toString());

    if (pRun == AnalysisRun.FEASIBILITY_CHECK) {
      builder.setOption("counterexample.checker", checkerType.name());

      switch (checkerType) {
        case CBMC:
          builder.setOption("cbmc.timelimit", timeForCex.toString());
          break;

        case CONCRETE_EXECUTION:
          builder.setOption("counterexample.concrete.timelimit", timeForCex.toString());
          break;

        case CPACHECKER:
          if (!Strings.isNullOrEmpty(cpacheckerConfigFile)) {
            try {
              builder.loadFromFile(cpacheckerConfigFile);
            } catch (IOException e) {
              throw new InvalidConfigurationException(
                  "Cannot read configuraton for counterexmple check from file: " + e.getMessage(),
                  e);
            }
          }
          // other checkers use only one time limit (XXX cpu or wall, lets say cpu)
          builder.setOption("limits.time.cpu", timeForCex.toString());
          break;

        default:
          throw new AssertionError("unexpected CEX checker type " + checkerType);
      }

    } else {
      // analysis
      builder.setOptions(getRoundLimitOptions(getLimitsForAnalysis()));
    }

    return builder
        .addConverter(FileOption.class, Configuration.getDefaultConverters().get(FileOption.class))
        .build();
  }

  private static Map<String, String> getRoundLimitOptions(
      ImmutableList<ResourceLimit> pRoundLimits) {
    ImmutableMap.Builder<String, String> result = ImmutableMap.builder();

    for (ResourceLimit limit : pRoundLimits) {
      String key;
      // time will be checked this time faster then limit is
      int exceedingToCheckingFactor = 1;

      if (limit instanceof ProcessCpuTimeLimit) {
        key = "limits.time.cpu";
        // cpu time checks are processor-count times more frequent
        exceedingToCheckingFactor = Runtime.getRuntime().availableProcessors();

      } else if (limit instanceof ThreadCpuTimeLimit) {
        key = "limits.time.cpu.thread";
        exceedingToCheckingFactor = 2;

      } else if (limit instanceof WalltimeLimit) {
        key = "limits.time.wall";
        // no factor
        exceedingToCheckingFactor = 1;

      } else {
        throw new AssertionError("unexpeted " + limit.getClass() + " class of time limit " + limit);
      }

      long wait = limit.nanoSecondsToNextCheck(limit.getCurrentValue()) * exceedingToCheckingFactor;
      result.put(key, String.format("%dns", wait));
    }

    return result.buildOrThrow();
  }

  public LogManager createRoundLogger(Configuration pConfig) throws InvalidConfigurationException {
    LoggingOptions logOptions = new LoggingOptions(pConfig);
    LogManager result = LogManager.createNullLogManager();

    Path logFile = logOptions.getOutputFile();
    if (logFile == null || logOptions.getFileLevel() == Level.OFF) {
      return result;
    }

    // create logger to given file
    Level fileLevel =
        fileLogLevel.intValue() <= logOptions.getFileLevel().intValue()
            ? fileLogLevel
            : logOptions.getFileLevel();

    try {
      MoreFiles.createParentDirectories(logFile);
      Handler outfileHandler = new FileHandler(logFile.toAbsolutePath().toString(), false);
      outfileHandler.setFilter(null);
      outfileHandler.setFormatter(TimestampedLogFormatter.withoutColors());
      outfileHandler.setLevel(fileLevel);
      result = BasicLogManager.createWithHandler(outfileHandler);

    } catch (IOException e) {
      // redirect log messages to console
      logger.logUserException(Level.WARNING, e, "Can not log to file");
    }

    return result;
  }

  private void startAnalysisLimits(LogManager roundLogger, ShutdownManager pShutdownManager) {
    currentLogger = roundLogger;
    currentTimer.start();

    ImmutableList<ResourceLimit> timeSpans = getLimitsForAnalysis();
    currentLimits = new ResourceLimitChecker(pShutdownManager, timeSpans);

    if (timeSpans.isEmpty()) {
      roundLogger.log(Level.INFO, "No resource limits for analysis round specified");

    } else {
      roundLogger.log(
          Level.INFO,
          "Using",
          Joiner.on(", ").join(Iterables.transform(timeSpans, ResourceLimit::getName)),
          "for analysis round");
    }

    currentLimits.start();
  }

  public CPAchecker createCpacheckerAndStartLimits(AnalysisRun pRun)
      throws InvalidConfigurationException {
    Configuration roundConfig = createRoundConfig(pRun);
    LogManager roundLogger = createRoundLogger(roundConfig);

    ShutdownNotifier parentNotifier = shutdownManager.getNotifier();
    ShutdownManager roundShutdownManager = ShutdownManager.createWithParent(parentNotifier);

    startAnalysisLimits(roundLogger, roundShutdownManager);
    return new CPAchecker(roundConfig, roundLogger, roundShutdownManager);
  }

  public void cancelAnalysisLimits() {
    currentLimits.cancel();
    currentTimer.stop();
    currentLogger.log(
        Level.INFO, "Used", currentTimer.getLengthOfLastInterval(), "for analysis round");
  }

  private void startCheckLimits(LogManager checkLogger, ShutdownManager pShutdownManager) {
    currentLogger = checkLogger;
    currentTimer.start();

    ImmutableList<ResourceLimit> timeSpans = produceLimitsFromNowOn(timeForCex);
    currentLimits = new ResourceLimitChecker(pShutdownManager, timeSpans);

    if (timeSpans.isEmpty()) {
      currentLogger.log(Level.INFO, "No resource limits for feasibility check specified");

    } else {
      currentLogger.log(
          Level.INFO,
          "Using",
          Joiner.on(", ").join(Iterables.transform(timeSpans, ResourceLimit::getName)),
          "for feasibility check");
    }

    currentLimits.start();
  }

  public CounterexampleChecker createCexCheckerAndStartLimits(AnalysisResult pResult)
      throws InvalidConfigurationException {
    assert pResult != null;

    Configuration checkConfig = createRoundConfig(AnalysisRun.FEASIBILITY_CHECK);
    LogManager checkLogger = createRoundLogger(checkConfig);

    ShutdownNotifier parentNotifier = shutdownManager.getNotifier();
    ShutdownManager fMan = ShutdownManager.createWithParent(parentNotifier);

    startCheckLimits(checkLogger, fMan);
    return createCexChecker(checkConfig, checkLogger, fMan, pResult);
  }

  private CounterexampleChecker createCexChecker(
      Configuration checkConfig,
      LogManager checkLogger,
      ShutdownManager pShutdownManager,
      AnalysisResult pResult)
      throws InvalidConfigurationException, AssertionError {

    ConfigurableProgramAnalysis usedCpa = pResult.getCpa();
    ARGCPA argCpa = CPAs.retrieveCPA(usedCpa, ARGCPA.class);
    if (argCpa == null) {
      throw new InvalidConfigurationException(
          "Counterexample check after CFA mutation expected ARG CPA, but got "
              + CPAs.asIterable(usedCpa).transform(cpa -> cpa.getClass().getSimpleName()));
    }

    CFA mutatedCfa = pResult.getCfa();
    return new DelegatingCheckerWithRestoredFunctions(
        checkConfig, checkLogger, mutatedCfa, pShutdownManager, checkerType, cfaMutator);
  }

  public void cancelCheckLimits() {
    currentLimits.cancel();
    currentTimer.stop();
    currentLogger.log(
        Level.INFO, "Used", currentTimer.getLengthOfLastInterval(), "for feasibility check");
  }

  public LogManager getCurrentLogger() {
    return currentLogger;
  }

  public File getIntermediateStatisticsFile() {
    FileTypeConverter fileConverter =
        (FileTypeConverter) Configuration.getDefaultConverters().get(FileOption.class);
    return fileConverter.getOutputPath().resolve(roundStatFile).toFile();
  }

  void printCfaNodeRank(PrintStream pOut, UnmodifiableReachedSet pReached) {
    pOut.println();
    pOut.println("Rank of CFA nodes by states:");
    pOut.println("============================");

    ImmutableList<Multiset.Entry<CFANode>> rankedNodes = rankNodes(pReached);

    int topSize = 15;
    ImmutableList<Entry<CFANode>> top =
        rankedNodes.subList(0, Math.min(rankedNodes.size(), topSize));
    {
      int i = 0;
      for (Multiset.Entry<CFANode> p : top) {
        pOut.println(++i + ". " + describeEntry(p));
      }
    }
    if (rankedNodes.size() > topSize) {
      pOut.println("... " + (rankedNodes.size() - topSize) + " more");
    }
    pOut.println();

    FileTypeConverter fileConverter =
        (FileTypeConverter) Configuration.getDefaultConverters().get(FileOption.class);
    Path thisRoundRankedNodesFile = fileConverter.getOutputPath().resolve(rankedNodesFile);
    try (Writer file = IO.openOutputFile(thisRoundRankedNodesFile, Charset.defaultCharset())) {
      int i = 0;
      for (Multiset.Entry<CFANode> p : rankedNodes) {
        file.append(++i + ". " + describeEntry(p));
      }
    } catch (IOException e) {
      logger.logUserException(Level.WARNING, e, "Cannot write node rank to file");
    }
  }

  private static String describeEntry(Multiset.Entry<CFANode> pEntry) {
    String result =
        pEntry.getElement().getFunctionName() + ":N" + pEntry.getElement().getNodeNumber();
    if (pEntry.getCount() != 1) {
      result += " x " + pEntry.getCount();
    }
    return result;
  }

  private static ImmutableList<Multiset.Entry<CFANode>> rankNodes(UnmodifiableReachedSet pReached) {
    Multiset<CFANode> nodes =
        TreeMultiset.create(
            FluentIterable.from(pReached).transformAndConcat(AbstractStates::extractLocations));

    return FluentIterable.from(nodes.entrySet())
        .toSortedList((p1, p2) -> p2.getCount() - p1.getCount());
  }
}
