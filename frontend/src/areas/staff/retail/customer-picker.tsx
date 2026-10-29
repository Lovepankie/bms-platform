import { useQueryClient } from '@tanstack/react-query';
import { useContext, type ReactNode } from 'react';
import { retail, type Customer } from '../../../api/retail';
import { AddByName } from '../../../app/ui/EmptyChoice';
import { PickerWithAdd } from '../../../app/ui/PickerWithAdd';
import { useStaff } from '../context';
import { canUse } from './permissions';
import { ScreenToast } from './toast';

// A customer chosen from the list on a sale, an order or a quotation (issue #688): the list ends with "+ Add a customer" for
// whoever may add customers (the Customers screen's permission), which adds one by name in a sheet over the form and chooses
// it, so the sale, order or quotation keeps everything typed. Its phone and terms are set later on the customer's own page.

const CUSTOMERS = ['retail', 'customers'];

export function CustomerPicker({ id, label, placeholder, customers, value, onChange, hint }: {
  id: string;
  label: ReactNode;
  /** The empty first choice, such as "No customer: retail prices". */
  placeholder: string;
  /** The read of the customers. */
  customers: { data?: Customer[]; isError: boolean; refetch: () => unknown };
  value: string;
  onChange: (customerId: string) => void;
  hint?: ReactNode;
}) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const screenToast = useContext(ScreenToast);
  return (
    <PickerWithAdd
      id={id}
      label={label}
      thing="customer"
      options={(customers.data ?? []).map((c) => ({ value: c.id ?? '', label: c.name ?? '' }))}
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      canAdd={canUse(me, 'buyers')}
      hint={hint}
      loading={customers.data === undefined}
      load={customers}
      empty="No customers yet."
      ask="Ask the owner to add your customers first."
      form={(done, cancel) => (
        <AddByName
          id={`${id}-add`}
          onCancel={cancel}
          add={{
            label: 'Add a customer',
            name: 'Name of the new customer',
            send: 'Add customer',
            maxLength: 200,
            create: (name) => retail.createCustomer({ name }),
            onAdded: (c: Customer) => {
              queryClient.setQueryData<Customer[]>(CUSTOMERS, (was) => was && [...was.filter((x) => x.id !== c.id), c]);
              void queryClient.invalidateQueries({ queryKey: CUSTOMERS });
              screenToast?.(`Added the customer ${c.name ?? ''}.`, 'success');
              done({ value: c.id ?? '', label: c.name ?? '' });
            },
          }}
        />
      )}
    />
  );
}
