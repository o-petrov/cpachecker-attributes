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

union u1 {
  struct s1 m1;
  struct s2 m2;
  struct s3 m3;
} w1;

union u2 {
  struct s1 m1;
  struct s2 m2;
  struct s3 m3;
} PACKED w2;

union u3 {
  struct s4 m1;
  struct s2 m2;
  struct s3 m3;
} w3;

union u4 {
  struct s4 m1;
  struct s2 m2;
  struct s3 m3;
} PACKED w4;

struct t5 {
  struct s5 m1;
  struct s6 m2;
  union u4 m3;
} w5;

int main() {
  PRINT(union u1)
  PRINT(w1)
  PRINTM(m1, w1, union u1)
  PRINTM(m2, w1, union u1)
  PRINTM(m3, w1, union u1)

  PRINT(union u2)
  PRINT(w2)
  PRINTM(m1, w2, union u2)
  PRINTM(m2, w2, union u2)
  PRINTM(m3, w2, union u2)

  PRINT(union u3)
  PRINT(w3)
  PRINTM(m1, w3, union u3)
  PRINTM(m2, w3, union u3)
  PRINTM(m3, w3, union u3)

  PRINT(union u4)
  PRINT(w4)
  PRINTM(m1, w4, union u4)
  PRINTM(m2, w4, union u4)
  PRINTM(m3, w4, union u4)

  PRINT(struct t5)
  PRINT(w5)
  PRINTM(m1, w5, struct t5)
  PRINTM(m2, w5, struct t5)
  PRINTM(m3, w5, struct t5)

  return 0;
}
