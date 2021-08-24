#include "prefix.h"

typedef long lt;
typedef long ALIGN(2) lat;

struct m {
  long m1;
} ALIGN(2);

struct s {
    char m0;
    struct m m1;
    long ALIGN(2) m2;
    lt m3;
    lt ALIGN(2) m4;
    lat m5;
    lat ALIGN(2) m6;
} ALIGN(2) v1;

struct s2 {
  struct s ALIGN(4) m1;
  char ALIGN(2) m2;
  char ALIGN(4) f[31];
} v2[3];

struct s ALIGN(1) a1[23];
typedef struct s ALIGN(1) aaa[23];

aaa v3, v4 ALIGN(8);

struct s2 ALIGN(1) v5;

typedef long ALIGN(2) la2;
la2 ALIGN(1) v6;

union u1 {
  long ALIGN(1) m2;
  lt m3;
  lat m4;
} v7;

union u2 {
  long ALIGN(1) m2;
  lat m4;
} v8;

union u3 {
  long ALIGN(1) m2;
  lat ALIGN(1) m4;
} v9;

int main() {
    PRINT(struct m)
    PRINT(v1)
    PRINTM(m0, v1, struct s)
    PRINTM(m1, v1, struct s)
    PRINTM(m2, v1, struct s)
    PRINTM(m3, v1, struct s)
    PRINTM(m4, v1, struct s)
    PRINTM(m5, v1, struct s)
    PRINTM(m6, v1, struct s)
    PRINT(struct s2)
    PRINT(v2)
    PRINTM(m1, v2[0], struct s2)
    PRINTM(m2, v2[0], struct s2)
    PRINTM(f, v2[0], struct s2)
    PRINTM(m1, v2[1], struct s2)
    PRINTM(m2, v2[1], struct s2)
    PRINTM(f, v2[1], struct s2)
    PRINT(aaa);
    PRINT(v3)
    PRINT(v3[0])
    PRINT(v3[1])
    PRINT(v4)
    PRINT(v4[0])
    PRINT(v4[1])
    PRINT(v5)
    PRINT(v6)
  	PRINT(a1)
  	PRINT(a1[0])

    PRINT(union u1)
    PRINTM(m2, v7, union u1)
    PRINTM(m3, v7, union u1)
    PRINTM(m4, v7, union u1)
    PRINT(union u2)
    PRINTM(m2, v8, union u2)
    PRINTM(m4, v8, union u2)
    PRINT(union u3)
    PRINTM(m2, v9, union u3)
    PRINTM(m4, v9, union u3)
    return 0;
}
