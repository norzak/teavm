#include <string.h>
#include <stdint.h>
#include <uchar.h>

#define HEADER_BITS_RESERVED 3

struct JavaObject;
struct JavaArray;
struct JavaClass;
struct JavaString;
typedef struct JavaObject JavaObject;
typedef struct JavaArray JavaArray;
typedef struct JavaClass JavaClass;
typedef struct JavaString JavaString;

#define PACK_CLASS(cls) (((uintptr_t) cls) >> HEADER_BITS_RESERVED)
#define UNPACK_CLASS(cls) (((JavaClass *) ((uintptr_t) cls << HEADER_BITS_RESERVED)))
#define CLASS_OF(obj) (((JavaClass *) ((uintptr_t) obj->header << HEADER_BITS_RESERVED)))
#define AS(ptr, type) ((type *) ptr)

#define VTABLE(obj, type) ((type *) UNPACK_CLASS(obj))
#define METHOD(obj, type, method) UNPACK_CLASS(obj)->method
#define FIELD(ptr, type, name) AS(ptr, type)->name;

#define TO_BYTE(i) (((i << 24) >> 24))
#define TO_SHORT(i) (((i << 16) >> 16))
#define TO_CHAR(i) ((char16_t) i)

#define COMPARE(a, b) (a > b ? 1 : a < b : -1 : 0)

#define ARRAY_LENGTH(array) (((JavaArray *) array)->length)
#define ARRAY_DATA(array, type) ((type *) (((JavaArray *) array) + 1))

#define CHECKCAST(obj, cls) (cls(obj) ? obj : throwClassCastException())
static JavaObject* throwClassCastException();

#define ALLOC_STACK(size) \
  void *__shadowStack__[size + 2]; \
  __shadowStack__[0] = stackTop; \
  stackTop = __shadowStack__

#define RELEASE_STACK stackTop = __shadowStack__[0]
#define GC_ROOT(index, ptr) __shadowStack__[2 + index] = ptr
#define GC_ROOT_RELEASE(index) __shadowStack__[2 + index] = NULL
#define CALL_SITE(id) __shadowStack__[1] = (void *) id

static void *stackTop;

static JavaString** stringPool;