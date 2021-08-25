# to test attributes aligned and packed
# ant && make

TESTDIR=test_attributes
DIR=.
RUN=$(DIR)/scripts/cpa.sh
OUT=$(DIR)/output
PROPS=-preprocess -default -setprop cfa.exportToC=true -setprop cfa.simplifyCfa=false
MACHINE=-64

TASKS= \
$(TESTDIR)/p01_scalar $(TESTDIR)/p02_typedef $(TESTDIR)/p03_struct \
$(TESTDIR)/p04_union $(TESTDIR)/p06_array1 $(TESTDIR)/p07_array2 \
$(TESTDIR)/p08_member $(TESTDIR)/p09 \
$(TESTDIR)/p10_enum $(TESTDIR)/p11 $(TESTDIR)/p12

all: $(TASKS)

%: %.c
	@echo $<
	gcc -Wall -Wextra $<
	./a.out > out.txt
	$(RUN) $(MACHINE) $(PROPS) $< > /dev/null 2> /dev/null
	gcc -Wno-format $(OUT)/cfa.c
	./a.out > cfa.txt
	diff cfa.txt out.txt > res.txt || cat res.txt
