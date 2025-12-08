-- !Ups

ALTER TABLE public.users RENAME COLUMN email TO email_hash;

-- !Downs

ALTER TABLE public.users RENAME COLUMN email_hash TO email;
