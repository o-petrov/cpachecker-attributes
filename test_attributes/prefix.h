extern int printf( const char *restrict format, ... );
extern void abort( void );
#include <stddef.h>
#define PACKED __attribute__((__packed__))
#define ALIGN(x) __attribute__((__aligned__(x)))
#define PRINT(x) printf(" " #x "\talign: %ld, size: %ld\n", _Alignof(x), sizeof(x));
#define PRINTM(m, v, t) \
PRINT(v.m) \
printf("" #t "\t" #v "." #m "\taddr diff is %ld, offsetof is %ld\n", (void*)(&v.m) - (void*)(&v), offsetof(t, m));
