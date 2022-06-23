// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = ((unsigned short) ~0ull) // max of unsigned short
} __attribute__((__packed__)) x;

int main () {
  x = -1ll;
  x = x >> 1;
  if (x != ((signed short) (((unsigned short) ~0ull) >> 1)) /* x != signed TYPE max */)
    goto ERROR;
  if (sizeof x != sizeof(short))
    goto ERROR;

  return 0;
ERROR:
  return 1;
}
