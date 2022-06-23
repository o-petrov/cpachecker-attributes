Test enumerated type has correct underlying (compatible) integer type.
Run benchmark with test/test-sets/enums.xml.

Enum is compatible with an integer type that can represent all enumerators of the enum.
If there are no negative enumerators, GCC chooses unsigned type.
If enum is packed, GCC chooses from char, short, int, long, long long.
If enum is not packed, GCC chooses from int, long, long long.

For enumerators GCC chooses int, if int can represent the value.
Otherwise GCC chooses the enum underlying type.
(So if packed enum is compaible with char, enumerators will be int.)

Test signedness with shift-* tests. (shift-safe-* tests have sizeof comparison too.)
Test size with memcpy-* tests. (Can not distinguish the types exactly, but it is enough.)

Template "macros":
TYPE is one of char, short, int, long, long
TYPE2 is TYPE or int, if int is greater
TYPE3 is TYPE or int, if int is smaller
LIMIT is one of signed min, signed max, unsigned max of the TYPE
CONDITION is essentially (for nonzero x) to check if x is < 0,
or to check if right shift of x kept the sign bit.
