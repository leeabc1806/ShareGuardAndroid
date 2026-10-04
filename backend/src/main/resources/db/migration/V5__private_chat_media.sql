-- Chat attachments live in a private bucket. Both room membership and uploader identity
-- are checked from the object path: room_id/user_id/random-file-name.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chat-media', 'chat-media', false, 20971520,
        array['image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm', 'video/quicktime'])
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists chat_media_read_members on storage.objects;
create policy chat_media_read_members on storage.objects
for select to authenticated using (
    bucket_id = 'chat-media' and exists (
        select 1 from public.chat_room_members m
        where m.room_id::text = (storage.foldername(name))[1]
          and m.user_id = (select auth.uid())
    )
);

drop policy if exists chat_media_insert_members on storage.objects;
create policy chat_media_insert_members on storage.objects
for insert to authenticated with check (
    bucket_id = 'chat-media'
    and (storage.foldername(name))[2] = (select auth.uid())::text
    and exists (
        select 1 from public.chat_room_members m
        where m.room_id::text = (storage.foldername(name))[1]
          and m.user_id = (select auth.uid())
    )
);

drop policy if exists chat_media_delete_own on storage.objects;
create policy chat_media_delete_own on storage.objects
for delete to authenticated using (
    bucket_id = 'chat-media' and owner_id = (select auth.uid())::text
);
