struct bare {
  unsigned int : 0;
  unsigned int first : 31 __attribute__((__aligned__(2)));
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 4u) flag = 1;
  if (_Alignof(v) != 2u) flag = 1;
  w[0] = 0u;
  w[1] = 0u;
  w[2] = 0u;
  w[3] = 0u;
  v.first = ~0u;
  if (w[0] != 255u) flag = 1;
  if (w[1] != 255u) flag = 1;
  if (w[2] != 255u) flag = 1;
  if (w[3] != 127u) flag = 1;

  if (flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
