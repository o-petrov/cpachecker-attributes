struct bare {
  unsigned char first;
  unsigned int second : 31 __attribute__((__aligned__));
} __attribute__((__packed__)) v;
#define offsetof(T, M) ((unsigned long) &(((T *) 0)->M))
void *malloc(unsigned long);
void free(void *);
int main() {
  void *p = malloc(sizeof(int));
  int flag = 0;
  unsigned char *w = (unsigned char *) &v;
  if (sizeof v != 32u) flag = 1;
  if (_Alignof(v) != 16u) flag = 1;
  if (sizeof v.first != 1u) flag = 1;
  if (_Alignof v.first != 1u) flag = 1;
  if (offsetof(struct bare, first) != 0u) flag = 1;
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
  w[16] = 0u;
  w[17] = 0u;
  w[18] = 0u;
  w[19] = 0u;
  w[20] = 0u;
  w[21] = 0u;
  w[22] = 0u;
  w[23] = 0u;
  w[24] = 0u;
  w[25] = 0u;
  w[26] = 0u;
  w[27] = 0u;
  w[28] = 0u;
  w[29] = 0u;
  w[30] = 0u;
  w[31] = 0u;
  v.first = ~0u;
  v.second = ~0u;
  if (w[0] != 255u) flag = 1;
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
  if (w[16] != 255u) flag = 1;
  if (w[17] != 255u) flag = 1;
  if (w[18] != 255u) flag = 1;
  if (w[19] != 127u) flag = 1;
  if (w[20] != 0u) flag = 1;
  if (w[21] != 0u) flag = 1;
  if (w[22] != 0u) flag = 1;
  if (w[23] != 0u) flag = 1;
  if (w[24] != 0u) flag = 1;
  if (w[25] != 0u) flag = 1;
  if (w[26] != 0u) flag = 1;
  if (w[27] != 0u) flag = 1;
  if (w[28] != 0u) flag = 1;
  if (w[29] != 0u) flag = 1;
  if (w[30] != 0u) flag = 1;
  if (w[31] != 0u) flag = 1;

  if (!flag) goto ERROR;
  free(p);
  return 0;
ERROR:
  return 1;
}