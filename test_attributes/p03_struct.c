#include "prefix.h"
// apperently struct variables can be less aligned than their type
struct s1 {
    char m1, m2;
} ALIGN(8);

struct s1 v1;
struct s1 v2 ALIGN(4);
struct s1 v3 ALIGN(16);

struct s2 {
    int m1;
    char m2;
} v4 ALIGN(16), v5 ALIGN(8);

struct s3 {
    char m1;
    char m2, m3;
} ALIGN(16) v6, /* ALIGN(8) */ v7, v8 ALIGN(8);

typedef struct s4 {
    char m1, m2;
    unsigned m3;
} ALIGN(16) aligned_struct;

aligned_struct v9;
aligned_struct v10, v11 ALIGN(8);
aligned_struct v12 ALIGN(2), v13 ALIGN(64);
aligned_struct ALIGN(16) v14;

struct s5 {
  long long m1;
} ALIGN(2);

struct s5 v15;

int main() {
    PRINT(v1);
    PRINT(v2);
    PRINT(v3);
    PRINT(v4);
    PRINT(v5);
    PRINT(v6);
    PRINT(v7);
    PRINT(v8);
    PRINT(v9);
    PRINT(v10);
    PRINT(v11);
    PRINT(v12);
    PRINT(v13);
    PRINT(v14);
    PRINT(v15);
    return 0;
}
