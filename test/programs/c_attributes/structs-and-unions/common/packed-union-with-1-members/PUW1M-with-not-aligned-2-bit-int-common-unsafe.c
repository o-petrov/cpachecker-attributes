union bare {
  unsigned int first : 2;
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 1u) flag = 1;
  if (_Alignof(v) != 1u) flag = 1;
  w[0] = 0u;
  v.first = ~0u;
  if (w[0] != 3u) flag = 1;

  if (!flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
