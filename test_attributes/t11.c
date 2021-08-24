#include "prefix.h"
// apperently struct variables can be less aligned than their type
struct s1 {
    char m1, m2;
} PACKED;

struct s1 v1;
struct s1 v2 PACKED;

struct s2 {
    int m1;
    char m2;
} v3 PACKED, v4;

typedef struct s3 {
    char m1, m2;
    unsigned m3;
} PACKED packed_struct;

packed_struct v5;
packed_struct v6, v7 PACKED;
packed_struct PACKED v8, v9;

struct s4 {
  char ALIGN(2) m1;
  int ALIGN(2) m2;
} PACKED v10;

int main() {
  PRINT(struct s1)
  PRINT(v1)
  PRINTM(m1, v1, struct s1)
  PRINTM(m2, v1, struct s1)
  PRINT(v2)
  PRINTM(m1, v2, struct s1)
  PRINTM(m2, v2, struct s1)

  PRINT(struct s2)
  PRINT(v3)
  PRINTM(m1, v3, struct s2)
  PRINTM(m2, v3, struct s2)
  PRINT(v4)
  PRINTM(m1, v4, struct s2)
  PRINTM(m2, v4, struct s2)

  PRINT(struct s3)
  PRINT(v5)
  PRINT(v6)
  PRINT(v7)
  PRINT(v8)
  PRINT(v9)

  PRINTM(m1, v5, struct s3)
  PRINTM(m2, v5, struct s3)
  PRINTM(m3, v5, struct s3)
  PRINTM(m1, v6, struct s3)
  PRINTM(m2, v6, struct s3)
  PRINTM(m3, v6, struct s3)
  PRINTM(m1, v7, struct s3)
  PRINTM(m2, v7, struct s3)
  PRINTM(m3, v7, struct s3)
  PRINTM(m1, v8, struct s3)
  PRINTM(m2, v8, struct s3)
  PRINTM(m3, v8, struct s3)
  PRINTM(m1, v9, struct s3)
  PRINTM(m2, v9, struct s3)
  PRINTM(m3, v9, struct s3)

  PRINT(struct s4)
  PRINT(v10)
  PRINTM(m1, v10, struct s4)
  PRINTM(m2, v10, struct s4)

  return 0;
}
