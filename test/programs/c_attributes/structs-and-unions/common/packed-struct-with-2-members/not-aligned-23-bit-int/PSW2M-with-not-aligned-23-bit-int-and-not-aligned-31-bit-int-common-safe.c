struct bare {
  unsigned int first : 23;
  unsigned int second : 31;
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 7u) flag = 1;
  if (_Alignof(v) != 1u) flag = 1;
  w[0] = 0u;
  w[1] = 0u;
  w[2] = 0u;
  w[3] = 0u;
  w[4] = 0u;
  w[5] = 0u;
  w[6] = 0u;
  v.first = ~0u;
  v.second = ~0u;
  if (w[0] != 255u) flag = 1;
  if (w[1] != 255u) flag = 1;
  if (w[2] != 255u) flag = 1;
  if (w[3] != 255u) flag = 1;
  if (w[4] != 255u) flag = 1;
  if (w[5] != 255u) flag = 1;
  if (w[6] != 63u) flag = 1;

  if (flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
