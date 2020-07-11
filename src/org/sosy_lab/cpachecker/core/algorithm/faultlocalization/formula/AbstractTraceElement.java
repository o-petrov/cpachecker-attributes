// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.faultlocalization.formula;

/**
 * Marker interface for types that can be a part of an abstract error trace.
 */
public interface AbstractTraceElement {

  //intended to exclusively be implemented by Selector and Interval

  @Override
  int hashCode();

  @Override
  boolean equals(Object q);

}
