import { mockMe } from '../api/retail-mock';
import { QuickAddForm } from '../areas/staff/retail/quick-add';
import { RETAIL_QUICK_ADD_TOURS } from './tours-retail-quick-add';
import { describeTours } from './test-kit';

// The page tour of Quick add (issue #632): the form is rendered as the user the tour is for, and each step's data-tour anchor
// must be in its markup.

const ADMIN = mockMe('admin');

describeTours('quick add', RETAIL_QUICK_ADD_TOURS, { 'page-quick-add': [ADMIN] }, () => <QuickAddForm />);
