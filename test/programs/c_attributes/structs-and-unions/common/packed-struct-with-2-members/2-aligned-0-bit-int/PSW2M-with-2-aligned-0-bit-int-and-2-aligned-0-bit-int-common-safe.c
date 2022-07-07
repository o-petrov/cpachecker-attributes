struct bare {
  unsigned int : 0 __attribute__((__aligned__(2)));
  unsigned int : 0 __attribute__((__aligned__(2)));
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  if (sizeof v != 0u) flag = 1;
  if (_Alignof(v) != 1u) flag = 1;

  if (flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
