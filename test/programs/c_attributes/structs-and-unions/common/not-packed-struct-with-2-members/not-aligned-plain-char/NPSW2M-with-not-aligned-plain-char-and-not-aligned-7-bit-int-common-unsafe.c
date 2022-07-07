struct bare {
  unsigned char first;
  unsigned int second : 7;
} v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 4u) flag = 1;
  if (_Alignof(v) != 4u) flag = 1;
  if (sizeof v.first != 1u) flag = 1;
  if (_Alignof v.first != 1u) flag = 1;
  if (offsetof(struct bare, first) != 0u) flag = 1;
  w[0] = 0u;
  w[1] = 0u;
  w[2] = 0u;
  w[3] = 0u;
  v.first = ~0u;
  v.second = ~0u;
  if (w[0] != 255u) flag = 1;
  if (w[1] != 127u) flag = 1;
  if (w[2] != 0u) flag = 1;
  if (w[3] != 0u) flag = 1;

  if (!flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
