-- !Ups

ALTER TABLE public.users RENAME COLUMN email_hash TO email_encrypted;

-- !Downs

ALTER TABLE public.users RENAME COLUMN email_encrypted TO email_hash;
