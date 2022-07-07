// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = LIMIT
} __attribute__((__packed__)) x;

int main () {
  x = -1ll;
  x = x >> 1;
  if (CONDITION)
    goto ERROR;
  if (sizeof x != sizeof(TYPE))
    goto ERROR;

  return 0;
ERROR:
  return 1;
}
