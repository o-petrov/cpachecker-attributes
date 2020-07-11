// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.faultlocalization.ranking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import java.util.stream.Collectors;

import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.util.faultlocalization.Fault;
import org.sosy_lab.cpachecker.util.faultlocalization.FaultRanking;
import org.sosy_lab.cpachecker.util.faultlocalization.FaultRankingUtils;
import org.sosy_lab.cpachecker.util.faultlocalization.appendables.FaultInfo;
import org.sosy_lab.cpachecker.util.faultlocalization.FaultRankingUtils.RankingResults;

public class MinimalLineDistanceRanking implements FaultRanking {

  private int errorLocation;

  /**
   * Sorts the result set by absolute distance to the error location based on the linenumber
   *
   * @param pErrorLocation the error location
   */
  public MinimalLineDistanceRanking(CFAEdge pErrorLocation) {
    errorLocation = pErrorLocation.getFileLocation().getStartingLineInOrigin();
  }

  @Override
  public List<Fault> rank(Set<Fault> result) {
    if(result.isEmpty()){
      return ImmutableList.of();
    }
    RankingResults ranking = FaultRankingUtils.rankedListFor(result,
        e -> e.stream()
            .mapToDouble(fc -> fc.correspondingEdge().getFileLocation().getStartingLineInOrigin())
            .max()
            .orElse(0.0));

    if(ranking.getRankedList().size()==1){
      Fault current = ranking.getRankedList().get(0);
      current.addInfo(FaultInfo.rankInfo(
          "Minimal distance to error location: " + Math.abs(errorLocation-(int)ranking.getLikelihoodMap().get(current).doubleValue()) + " line(s)",
          1d));
      return ranking.getRankedList();
    }

    /* Example: dist(Fault A) = 10, dist(Fault B) = 5, dist(Fault C) = 5, dist(Fault D) = 1.
     * Expected Likelihood: L(Fault A) = 1/9, L(Fault B) = 2/9, L(Fault C) = 2/9, L(Fault D) = 4/9.
     * If two faults A and B do not have the same distance and are ranked exactly 1 apart,
     * L(A) = 2*L(B) must hold. The code below ensures this.
     * */
    List<Double> sortedLikelihood = ranking.getLikelihoodMap().values().stream().distinct().sorted().collect(Collectors.toList());
    Map<Double, Integer> index = new HashMap<>();
    for(int i = 0; i < sortedLikelihood.size(); i++){
      index.put(sortedLikelihood.get(i), i);
    }

    int total = 0;

    for(Double val: ranking.getLikelihoodMap().values()){
      total += 1<<index.get(val);
    }

    double single = 1d/total;

    for(Map.Entry<Fault, Double> entry: ranking.getLikelihoodMap().entrySet()){
      Fault current = entry.getKey();
      current.addInfo(FaultInfo.rankInfo(
              "Minimal distance to error location: " + Math.abs(errorLocation-(int)ranking.getLikelihoodMap().get(current).doubleValue()) + " line(s)",
              Math.pow(2, index.get(entry.getValue()))*single));
    }

    return ranking.getRankedList();

  }
}
