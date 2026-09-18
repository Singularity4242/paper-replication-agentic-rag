import { queryOptions } from '@tanstack/react-query';
import { librariesApi } from './api';
export const librariesQuery = queryOptions({ queryKey: ['libraries'], queryFn: librariesApi.list });
