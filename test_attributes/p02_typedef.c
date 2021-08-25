#include "prefix.h"
typedef char ALIGN(8) aligned;
aligned v1;
aligned v2 ALIGN(4);
aligned v3 ALIGN(16);
aligned v4 ALIGN(8);
aligned ALIGN(4) v5;
aligned ALIGN(8) v6;
aligned ALIGN(16) v7;
aligned v8 ALIGN(4), v9 ALIGN(16);

typedef aligned ALIGN(16) da;
da v10;
da v11 ALIGN(4);
da v12 ALIGN(16);
da v13 ALIGN(8);
da ALIGN(4) v14;
da ALIGN(8) v15;
da ALIGN(16) v16;
da v17 ALIGN(4), v18 ALIGN(16);

typedef int afteraligned ALIGN(16);

afteraligned v19;
afteraligned ALIGN(8) v20, v21 ALIGN(32), v22;

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
    return 0;
}
