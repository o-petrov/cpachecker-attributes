Programs in this folder test aligned fields (including bit-fields)
of structs and unions.

Tests include empty structs and unions, unions with one member,
structs with two members. Structs and unions are packed and not packed.

To check offsets of bit-fields, bytes of struct are set to 00...00,
then fields of structs are set to 11...11.
Then values of bytes are checked one-by-one to see which bits are actually set.

If bit-field is less aligned than its type, but its bitsize
exceeeds this alignment, GCC and Clang may generate different offsets.
In this case both versions are pressent in respective subdirectory.

If both compilers agree, the single test is present in 'common/' subdirectory.
