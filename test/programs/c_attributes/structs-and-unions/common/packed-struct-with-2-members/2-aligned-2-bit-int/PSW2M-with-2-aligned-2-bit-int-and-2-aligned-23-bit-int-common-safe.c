struct bare {
  unsigned int first : 2 __attribute__((__aligned__(2)));
  unsigned int second : 23 __attribute__((__aligned__(2)));
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 6u) flag = 1;
  if (_Alignof(v) != 2u) flag = 1;
  w[0] = 0u;
  w[1] = 0u;
  w[2] = 0u;
  w[3] = 0u;
  w[4] = 0u;
  w[5] = 0u;
  v.first = ~0u;
  v.second = ~0u;
  if (w[0] != 3u) flag = 1;
  if (w[1] != 0u) flag = 1;
  if (w[2] != 255u) flag = 1;
  if (w[3] != 255u) flag = 1;
  if (w[4] != 127u) flag = 1;
  if (w[5] != 0u) flag = 1;

  if (flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
