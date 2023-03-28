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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutator;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.core.CPAcheckerMutator.AnalysisResult;
import org.sosy_lab.cpachecker.core.CPAcheckerMutator.AnalysisRun;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CBMCChecker;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.ConcretePathExecutionChecker;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleCPAchecker;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleCheckAlgorithm.CounterexampleCheckerType;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleChecker;
import org.sosy_lab.cpachecker.core.counterexample.AssumptionToEdgeAllocator;
import org.sosy_lab.cpachecker.core.counterexample.CounterexampleInfo;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CounterexampleAnalysisFailed;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.LiveVariables;
import org.sosy_lab.cpachecker.util.LoopStructure;
import org.sosy_lab.cpachecker.util.cwriter.CFAToCTranslator;
import org.sosy_lab.cpachecker.util.cwriter.TranslatorConfig;
import org.sosy_lab.cpachecker.util.resources.ProcessCpuTime;
import org.sosy_lab.cpachecker.util.resources.ProcessCpuTimeLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.resources.ThreadCpuTimeLimit;
import org.sosy_lab.cpachecker.util.resources.WalltimeLimit;
import org.sosy_lab.cpachecker.util.variableclassification.VariableClassification;

@Options(prefix = "cfaMutation")
final class CFAMutationManager {

  @Option(
      secure = true,
      name = "logFilesLevel",
      description =
          "Create log file for every round with this logging level.\n"
              + "Use main file logging level if that is lower.")
  private Level fileLogLevel = Level.FINE;

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
              + "CFA mutations. Currently CBMC, CPAchecker with a different config, or the concrete "
              + "execution checker can be used.")
  private CounterexampleCheckerType checkerType = CounterexampleCheckerType.CBMC;

  @Option(
      secure = true,
      name = "cex.checker.config",
      description = "If CPAchecker is used as CEX-checker, it uses this configuration file.")
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

    if (checkerType == CounterexampleCheckerType.CPACHECKER
        && Strings.isNullOrEmpty(cpacheckerConfigFile)) {
      throw new InvalidConfigurationException(
          "CEX check to validate an error found during CFA mutations uses CPAchecker, "
              + "but no config was specified for it");
    }

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
      logger.log(Level.INFO, pDescription, shutdownManager.getNotifier().getReason());
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
        logger.log(Level.INFO, pDescription, globalLimit.getName(), pWillExceed);
        return true;
      }
    }

    return false;
  }

  public Configuration createRoundConfig(AnalysisRun pRun) throws InvalidConfigurationException {
    ConfigurationBuilder builder = Configuration.builder().copyFrom(config);

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
          builder.setOption("counterexample.checker.config", cpacheckerConfigFile);
          // other checkers use only one time limit (XXX cpu or wall, lets say cpu)
          builder.setOption("limits.time.cpu", timeForCex.toString());
          break;

        default:
          throw new AssertionError("unexpected CEX checker type " + checkerType);
      }

    } else {
      // analysis
      builder.setOptions(getRoundLimitOptions());
    }

    return builder
        .addConverter(FileOption.class, Configuration.getDefaultConverters().get(FileOption.class))
        .build();
  }

  private Map<String, String> getRoundLimitOptions() {
    ImmutableMap.Builder<String, String> result = ImmutableMap.builder();
    ImmutableList<ResourceLimit> roundLimits = getLimitsForAnalysis();

    for (ResourceLimit limit : roundLimits) {
      String key;
      if (limit instanceof ProcessCpuTimeLimit) {
        key = "limits.time.cpu";
      } else if (limit instanceof ThreadCpuTimeLimit) {
        key = "limits.time.cpu.thread";
      } else if (limit instanceof WalltimeLimit) {
        key = "limits.time.wall";
      } else {
        throw new AssertionError("unexpeted " + limit.getClass() + " class of time limit " + limit);
      }

      result.put(key, String.format("%dns", limit.nanoSecondsToNextCheck(limit.getCurrentValue())));
    }

    return result.build();
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
    return createCexChecker(checkConfig, checkLogger, fMan.getNotifier(), pResult);
  }

  private CounterexampleChecker createCexChecker(
      Configuration checkConfig,
      LogManager checkLogger,
      ShutdownNotifier pShutdownNotifier,
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
    NavigableSet<String> alreadyPresentFunctions = mutatedCfa.getAllFunctionNames();

    switch (checkerType) {
      case CBMC:
        return new CBMCChecker(checkConfig, checkLogger, mutatedCfa) {
          @Override
          public void writeCexFile(
              ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path cFile)
              throws CounterexampleAnalysisFailed {
            super.writeCexFile(pRootState, pErrorState, pErrorPathStates, cFile);
            writeRestoredFunctions(cFile, alreadyPresentFunctions);
          }
        };

      case CPACHECKER:
        AssumptionToEdgeAllocator assumptionToEdgeAllocator =
            AssumptionToEdgeAllocator.create(
                checkConfig, checkLogger, mutatedCfa.getMachineModel());

        Function<ARGState, Optional<CounterexampleInfo>> tryGetCex =
            s ->
                ARGUtils.tryGetOrCreateCounterexampleInformation(
                    s, usedCpa, assumptionToEdgeAllocator);

        return new CounterexampleCPAchecker(
            checkConfig, originalSpec, checkLogger, pShutdownNotifier, mutatedCfa, tryGetCex) {
          @Override
          public void writeCexFile(
              ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path cFile)
              throws CounterexampleAnalysisFailed, InterruptedException {
            super.writeCexFile(pRootState, pErrorState, pErrorPathStates, cFile);
            writeRestoredFunctions(cFile, alreadyPresentFunctions);
          }
        };

      case CONCRETE_EXECUTION:
        return new ConcretePathExecutionChecker(checkConfig, checkLogger, mutatedCfa) {
          @Override
          public void writeCexFile(
              ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path cFile)
              throws CounterexampleAnalysisFailed {
            super.writeCexFile(pRootState, pErrorState, pErrorPathStates, cFile);
            writeRestoredFunctions(cFile, alreadyPresentFunctions);
          }
        };

      default:
        throw new AssertionError("Unhandled case statement: " + checkerType);
    }
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

  /**
   * @param pCFile File that contains counterexample to check. Definitions of functions that were
   *     removed when the counterexample was found will be added to this file.
   * @param pAlreadyPresentFunctions Names of functions that were present during CEX export.
   */
  @SuppressWarnings("unused")
  private void writeRestoredFunctions(Path pCFile, NavigableSet<String> pAlreadyPresentFunctions)
      throws CounterexampleAnalysisFailed {
    CFA restoredPart;
    try {
      CFA restoredCfa = cfaMutator.restoreCfa();
      restoredPart = new AdditionalFunctionsCFA(restoredCfa, pAlreadyPresentFunctions);
    } catch (InvalidConfigurationException | InterruptedException | ParserException e) {
      throw new CounterexampleAnalysisFailed(
          "Cannot restore CFA for proper counterexample check: " + e.getMessage(), e);
    }

    try {
      TranslatorConfig transConfig = new TranslatorConfig(config);
      transConfig.setIncludeHeader(false);
      String code = new CFAToCTranslator(transConfig).translateCfa(restoredPart);
      try (Writer writer =
          IO.openOutputFile(pCFile, Charset.defaultCharset(), StandardOpenOption.APPEND)) {
        writer.write("\n// Above is counterexample to check.\n// Below are restored functions.\n");
        writer.write(code);
      }

    } catch (CPAException | InvalidConfigurationException | IOException e) {
      throw new CounterexampleAnalysisFailed(
          "Cannot add definitions of absent functons to the counterexample file "
              + "produced with mutated CFA: "
              + e.getMessage(),
          e);
    }
  }

  private static final class AdditionalFunctionsCFA implements CFA {
    private final CFA delegate;
    private final NavigableMap<String, FunctionEntryNode> functions = new TreeMap<>();

    AdditionalFunctionsCFA(CFA pRestoredCfa, NavigableSet<String> pAlreadyPresentFunctions) {
      assert pRestoredCfa.getAllFunctionNames().containsAll(pAlreadyPresentFunctions);
      delegate = pRestoredCfa;
      functions.putAll(pRestoredCfa.getAllFunctions());
      functions.keySet().removeAll(pAlreadyPresentFunctions);
    }

    @Override
    public MachineModel getMachineModel() {
      return delegate.getMachineModel();
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public int getNumberOfFunctions() {
      return functions.size();
    }

    @Override
    public NavigableSet<String> getAllFunctionNames() {
      return (NavigableSet<String>) functions.keySet();
    }

    @Override
    public Collection<FunctionEntryNode> getAllFunctionHeads() {
      return functions.values();
    }

    @Override
    public FunctionEntryNode getFunctionHead(String pName) {
      FunctionEntryNode result = functions.get(pName);
      assert result != null
          : "Function "
              + pName
              + " not found, and original CFA "
              + (delegate.getAllFunctionNames().contains(pName) ? "does " : "does not ")
              + "contain this function";
      return result;
    }

    @Override
    public NavigableMap<String, FunctionEntryNode> getAllFunctions() {
      return functions;
    }

    @Override
    public Collection<CFANode> getAllNodes() {
      return delegate.getAllNodes();
    }

    @Override
    public FunctionEntryNode getMainFunction() {
      return delegate.getMainFunction();
    }

    @Override
    public Optional<LoopStructure> getLoopStructure() {
      return delegate.getLoopStructure();
    }

    @Override
    public Optional<ImmutableSet<CFANode>> getAllLoopHeads() {
      return delegate.getAllLoopHeads();
    }

    @Override
    public Optional<VariableClassification> getVarClassification() {
      return delegate.getVarClassification();
    }

    @Override
    public Optional<LiveVariables> getLiveVariables() {
      return delegate.getLiveVariables();
    }

    @Override
    public Language getLanguage() {
      return delegate.getLanguage();
    }

    @Override
    public List<Path> getFileNames() {
      return delegate.getFileNames();
    }
  }
}
