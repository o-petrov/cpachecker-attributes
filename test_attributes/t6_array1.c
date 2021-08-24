#include "prefix.h"
 // same bug as t1: v17 v18, ignoring align 32 and align 64
char v1[1];
char ALIGN(8) v2[3];
char v3[3] ALIGN(4);
char v4[3] ALIGN(2), v5[3] ALIGN(4);
char ALIGN(4) v6[3] ALIGN(2);
char ALIGN(4) v7[3] ALIGN(4);
char ALIGN(4) v8[3] ALIGN(8);

int v9[3], v10[3] ALIGN(8), v11[3] ALIGN(2);
unsigned ALIGN(16) v12[3], ALIGN(8) v13[3];

int ALIGN(8) v14[3] ALIGN(16);
int ALIGN(16) v15[3] ALIGN(8);
int ALIGN(8) v16[3] ALIGN(16), /* ALIGN(32) */ v17[3] ALIGN(16), /* ALIGN(64) */ v18[3] ALIGN(2);
int ALIGN(2) v19[3] ALIGN(2);

long double ALIGN(1) v20[3];
long double v21[3] ALIGN(1), v22[3] ALIGN(2);
long double ALIGN(64) v23[3];

int main() {
    PRINT(v1)
    PRINT(v1[0])
    PRINT(v2)
    PRINT(v2[0])
    PRINT(v3)
    PRINT(v3[0])
    PRINT(v4)
    PRINT(v4[0])
    PRINT(v5)
    PRINT(v5[0])
    PRINT(v6)
    PRINT(v6[0])
    PRINT(v7)
    PRINT(v7[0])
    PRINT(v8)
    PRINT(v8[0])
    PRINT(v9)
    PRINT(v9[0])
    PRINT(v10)
    PRINT(v10[0])
    PRINT(v11)
    PRINT(v11[0])
    PRINT(v12)
    PRINT(v12[0])
    PRINT(v13)
    PRINT(v13[0])
    PRINT(v14)
    PRINT(v14[0])
    PRINT(v15)
    PRINT(v15[0])
    PRINT(v16)
    PRINT(v16[0])
    PRINT(v17)
    PRINT(v17[0])
    PRINT(v18)
    PRINT(v18[0])
    PRINT(v19)
    PRINT(v19[0])
    PRINT(v20)
    PRINT(v20[0])
    PRINT(v21)
    PRINT(v21[0])
    PRINT(v22)
    PRINT(v22[0])
    PRINT(v23)
    PRINT(v23[0])
    return 0;
}
