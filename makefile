# to test attributes aligned and packed
# ant && make

TESTDIR=test_attributes
DIR=.
RUN=$(DIR)/scripts/cpa.sh
OUT=$(DIR)/output
PROPS=-preprocess -default -setprop cfa.exportToC=true -setprop cfa.simplifyCfa=false
MACHINE=-64

TASKS= \
$(TESTDIR)/t1_scalar $(TESTDIR)/t2_typedef $(TESTDIR)/t3_struct \
$(TESTDIR)/t4_union $(TESTDIR)/t6_array1 $(TESTDIR)/t7_array2 \
$(TESTDIR)/t8_member $(TESTDIR)/t9 \
$(TESTDIR)/t10_enum $(TESTDIR)/t11 $(TESTDIR)/t12

all: $(TASKS)

%: %.c
	@echo $<
	gcc -Wall -Wextra $<
	./a.out > out.txt
	$(RUN) $(MACHINE) $(PROPS) $< > /dev/null 2> /dev/null
	gcc -Wno-format $(OUT)/cfa.c
	./a.out > cfa.txt
	diff cfa.txt out.txt > res.txt || cat res.txt
