#include "prefix.h"

enum E0 {
  a0 = 0
} v0 = a0;
// cdt ignores, gcc does not
// enum PACKED E1 {
//   a1 = -1
// } v1 = a1;
enum E2 {
  a2 = 127
} PACKED v2 = a2;
enum E3 {
  a3 = -128
} v3 PACKED;
// gcc ignores, but cdt recognises
// PACKED enum E4 {
//   a4 = 255
// } v4 = a4;

enum PACKED E10 {
  a10 = 1234567890123456789
} v10 = a10;

int main() {
  PRINT(enum E0)
  PRINT(a0)
  PRINT(v0)
  // PRINT(enum E1)
  // PRINT(a1)
  // PRINT(v1)
  PRINT(enum E2)
  PRINT(a2)
  PRINT(v2)
  PRINT(enum E3)
  PRINT(a3)
  PRINT(v3)
  // PRINT(enum E4)
  // PRINT(a4)
  // PRINT(v4)
  PRINT(enum E10)
  PRINT(a10)
  PRINT(v10)

  return 0;
}
