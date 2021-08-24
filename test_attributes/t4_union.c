#include "prefix.h"
union u1 {
    char m1;
    int m2;
} ALIGN(8);
union u1 v1, v2;
union u1 v3 ALIGN(4);

union u2 {
    char m1;
    char m2;
    short m3;
} v4 ALIGN(8), v5;

typedef union {
    char m1;
} alias;
alias ALIGN(16) v6, v7 ALIGN(4);
typedef alias ALIGN(2) aligned;
aligned ALIGN(4) v8, v9 ALIGN(8);

union u3 {
  long long l1;
} ALIGN(2) v10;
union u3 v11 ALIGN(4);

union u4 {
  char m1;
  long long l1;
} ALIGN(2) v12;

int main() {
    PRINT(v1)
    PRINT(v2)
    PRINT(v3)
    PRINT(v4)
    PRINT(v5)
    PRINT(v6)
    PRINT(v7)
    PRINT(v8)
    PRINT(v9)
    PRINT(union u3)
    PRINT(v10)
    PRINT(v11)
    PRINT(union u4)
    PRINT(v12)
    return 0;
}
