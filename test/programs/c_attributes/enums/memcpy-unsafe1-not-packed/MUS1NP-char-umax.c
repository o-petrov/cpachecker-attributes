// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = ((unsigned char) ~0ull) // max of unsigned char
} x;

int main () {
  char y;
  memcpy(&y, &x, sizeof x);
  memcpy(&x, &y, sizeof y);
  return 0;
}
