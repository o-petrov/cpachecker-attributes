#include "prefix.h"

struct __attribute__ ((aligned)) S { short f[13]; };

struct s1 {
    char ALIGN(8) m1;
};
struct s1 v1, v2;

struct s2 {
    char ALIGN(8) m1;
} v3, v4;

struct s3 {
    char ALIGN(8) m1, m2;
} v5, v6;

struct s4 {
    long ALIGN(1) m1;
} v7, v8;

struct s5 {
    long ALIGN(1) m1, m2;
} v9, v10;

struct s6 {
    struct s1 m1;
} v11, v12;

struct s7 {
    struct s5 m1;
} v13, v14;

struct s8 {
    char ALIGN(8) m1;
    char m2;
} v15, v16;

struct s9 {
    char m1;
    char ALIGN(8) m2;
} v17, v18;

struct s10 {
    long m1;
    long ALIGN(1) m2;
} v19, v20;

struct s11 {
    long ALIGN(1) m1;
    long m2;
} v21, v22;

int main() {
    PRINT(struct S);
    PRINTM(m1, v1, struct s1);
    PRINTM(m1, v2, struct s1);
    PRINTM(m1, v3, struct s2);
    PRINTM(m1, v4, struct s2);
    PRINTM(m1, v5, struct s3);
    PRINTM(m2, v5, struct s3);
    PRINTM(m1, v6, struct s3);
    PRINTM(m2, v6, struct s3);
    PRINTM(m1, v7, struct s4);
    PRINTM(m1, v8, struct s4);
    PRINTM(m1, v9, struct s5);
    PRINTM(m2, v9, struct s5);
    PRINTM(m1, v10, struct s5);
    PRINTM(m2, v10, struct s5);
    PRINTM(m1, v11, struct s6);
    PRINTM(m1, v12, struct s6);
    PRINTM(m1, v13, struct s7);
    PRINTM(m1, v14, struct s7);
    PRINTM(m1, v15, struct s8);
    PRINTM(m2, v15, struct s8);
    PRINTM(m1, v16, struct s8);
    PRINTM(m2, v16, struct s8);
    PRINTM(m1, v17, struct s9);
    PRINTM(m2, v17, struct s9);
    PRINTM(m1, v18, struct s9);
    PRINTM(m2, v18, struct s9);
    PRINTM(m1, v19, struct s10);
    PRINTM(m2, v19, struct s10);
    PRINTM(m1, v20, struct s10);
    PRINTM(m2, v20, struct s10);
    PRINTM(m1, v21, struct s11);
    PRINTM(m2, v21, struct s11);
    PRINTM(m1, v22, struct s11);
    PRINTM(m2, v22, struct s11);
    return 0;
}
