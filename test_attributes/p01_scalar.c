#include "prefix.h"
// Bug: ALIGN before variable is ignored (must be processed as ALIGN after variable)
char v1;
char ALIGN(8) v2;
char v3 ALIGN(4);
char v4 ALIGN(2), v5 ALIGN(4);
char ALIGN(4) v6 ALIGN(2);
char ALIGN(4) v7 ALIGN(4);
char ALIGN(4) v8 ALIGN(8);

int v9, v10 ALIGN(8), v11 ALIGN(2);
unsigned ALIGN(16) v12, ALIGN(8) v13, v26;
short ALIGN(8) v24, /* ALIGN(16) */ v25, v27;

int ALIGN(8) v14 ALIGN(16);
int ALIGN(16) v15 ALIGN(8);
int ALIGN(8) v16 ALIGN(16), /* ALIGN(32) */ v17 ALIGN(16), /* ALIGN(64) */ v18 ALIGN(2);
int ALIGN(2) v19 ALIGN(2);

long double ALIGN(1) v20;
long double v21 ALIGN(1), v22 ALIGN(2);
long double ALIGN(64) v23;

long ALIGN(1) v28;

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
    PRINT(v10)
    PRINT(v11)
    PRINT(v12)
    PRINT(v13)
    PRINT(v14)
    PRINT(v15)
    PRINT(v16)
    PRINT(v17)
    PRINT(v18)
    PRINT(v19)
    PRINT(v20)
    PRINT(v21)
    PRINT(v22)
    PRINT(v23)
    PRINT(v24)
    PRINT(v25)
    PRINT(v26)
    PRINT(v27)
    PRINT(v28)
    return 0;
}
