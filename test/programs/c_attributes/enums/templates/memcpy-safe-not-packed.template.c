// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = LIMIT
} x;

int main () {
  TYPE2 y;
  memcpy(&y, &x, sizeof x);
  memcpy(&x, &y, sizeof y);

  void *p = malloc(sizeof c);
  if (p == 0) return 0;

  TYPE2 z;
  memcpy(&z, p, sizeof c);
  memcpy(p, &z, sizeof z);

  free(p);
  return 0;
}
