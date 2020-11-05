typedef _Bool bool;
typedef unsigned long __kernel_ulong_t;
typedef __kernel_ulong_t __kernel_size_t;
typedef __kernel_size_t size_t;
void *__devm_regmap_init_i2c(void);
void __VERIFIER_assume(int);
static bool IS_ERR(void const *ptr);
static long PTR_ERR(void const *ptr);
char *r;

void *ldv_reference_malloc(size_t size)
{
  void *res;
  res = malloc(size);
    __VERIFIER_assume(res != (void *)0);
    return res;
}

void *ldv_malloc(size_t size)
{
  void *res;
  res = ldv_reference_malloc(size);
  if (res != (void *)0) {
    __VERIFIER_assume(ldv_is_err((void const *)res) == 0L);
  }
  return res;
}
static int sx9500_probe() {
//  void *data = __devm_regmap_init_i2c();
  void *data = 18446744073709547522UL;
  if (ldv_is_err(data))
    return ldv_ptr_err(data);
  r = ldv_malloc(2);
  return 0;
}

//static int ldv_filter_positive_int(int val)
//{
//  __VERIFIER_assume(val <= 0);
//  return val;
//}
//
//int ldv_post_probe(int probe_ret_val) {
//  return ldv_filter_positive_int(probe_ret_val);
//}

int main() {
  int emg_7_probe_retval = sx9500_probe();
//  emg_7_probe_retval = ldv_post_probe(emg_7_probe_retval);
  if (ldv_undef_int()) {
    __VERIFIER_assume(emg_7_probe_retval == 0);
    r[1] = 1;
    free(r);
  } else {
    __VERIFIER_assume(emg_7_probe_retval != 0);
  }
}

long ldv_is_err(void const *ptr)
{
  return (long)((unsigned long)ptr > 18446744073709547521UL);
}

long ldv_ptr_err(void const *ptr)
{
  __VERIFIER_assume((unsigned long)ptr > 18446744073709547521UL);
  return (long)(18446744073709547521UL - (unsigned long)ptr);
}

static bool IS_ERR(void const *ptr)
{
  long ret;
  ret = ldv_is_err(ptr);
  return (_Bool)(ret != 0L);
}

static long PTR_ERR(void const *ptr)
{
  return ldv_ptr_err(ptr);
}