// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = ((signed long) (((unsigned long) ~0ull) >> 1)) // max of signed long
} x;

int main () {
  void *p = malloc(sizeof c);
  if (p == 0) return 0;

  int z;
  memcpy(&z, p, sizeof c);
  memcpy(p, &z, sizeof z);

  free(p);
  return 0;
}
