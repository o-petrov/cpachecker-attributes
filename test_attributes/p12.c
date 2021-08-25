#include "prefix.h"
// apperently struct variables can be less aligned than their type
struct s1 {
    char m1;
    short m2;
} PACKED v1;

struct s2 {
    char ALIGN(2) m1;
    short m2;
} PACKED v2;

struct s3 {
    char m1;
    short ALIGN(1) m2;
} PACKED v3;

struct s4 {
    char ALIGN(4) m1;
    short ALIGN(4) m2;
} PACKED v4;

struct s5 {
    short m1;
    int m2;
} PACKED v5;

struct s6 {
    short m1;
    int ALIGN(2) m2;
} PACKED v6;

struct s7 {
    short ALIGN(4) m1;
    int m2;
} PACKED v7;

struct s8 {
    short m1;
    int ALIGN(1) m2;
} PACKED v8;

int main() {
  PRINT(struct s1)
  PRINT(v1)
  PRINTM(m1, v1, struct s1)
  PRINTM(m2, v1, struct s1)

  PRINT(struct s2)
  PRINT(v2)
  PRINTM(m1, v2, struct s2)
  PRINTM(m2, v2, struct s2)

  PRINT(struct s3)
  PRINT(v3)
  PRINTM(m1, v3, struct s3)
  PRINTM(m2, v3, struct s3)

  PRINT(struct s4)
  PRINT(v4)
  PRINTM(m1, v4, struct s4)
  PRINTM(m2, v4, struct s4)

  PRINT(struct s5)
  PRINT(v5)
  PRINTM(m1, v5, struct s5)
  PRINTM(m2, v5, struct s5)

  PRINT(struct s6)
  PRINT(v6)
  PRINTM(m1, v6, struct s6)
  PRINTM(m2, v6, struct s6)

  PRINT(struct s7)
  PRINT(v7)
  PRINTM(m1, v7, struct s7)
  PRINTM(m2, v7, struct s7)

  PRINT(struct s8)
  PRINT(v8)
  PRINTM(m1, v8, struct s8)
  PRINTM(m2, v8, struct s8)

  return 0;
}
