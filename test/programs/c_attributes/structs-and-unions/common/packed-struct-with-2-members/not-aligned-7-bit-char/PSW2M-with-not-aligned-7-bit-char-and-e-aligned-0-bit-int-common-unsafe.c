struct bare {
  unsigned char first : 7;
  unsigned int : 0 __attribute__((__aligned__));
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 16u) flag = 1;
  if (_Alignof(v) != 1u) flag = 1;
  w[0] = 0u;
  w[1] = 0u;
  w[2] = 0u;
  w[3] = 0u;
  w[4] = 0u;
  w[5] = 0u;
  w[6] = 0u;
  w[7] = 0u;
  w[8] = 0u;
  w[9] = 0u;
  w[10] = 0u;
  w[11] = 0u;
  w[12] = 0u;
  w[13] = 0u;
  w[14] = 0u;
  w[15] = 0u;
  v.first = ~0u;
  if (w[0] != 127u) flag = 1;
  if (w[1] != 0u) flag = 1;
  if (w[2] != 0u) flag = 1;
  if (w[3] != 0u) flag = 1;
  if (w[4] != 0u) flag = 1;
  if (w[5] != 0u) flag = 1;
  if (w[6] != 0u) flag = 1;
  if (w[7] != 0u) flag = 1;
  if (w[8] != 0u) flag = 1;
  if (w[9] != 0u) flag = 1;
  if (w[10] != 0u) flag = 1;
  if (w[11] != 0u) flag = 1;
  if (w[12] != 0u) flag = 1;
  if (w[13] != 0u) flag = 1;
  if (w[14] != 0u) flag = 1;
  if (w[15] != 0u) flag = 1;

  if (!flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}
