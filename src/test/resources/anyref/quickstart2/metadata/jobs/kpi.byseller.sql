with mycte as (
    select seller_email, amount
    from sellers hrs, orders sos where hrs.id = sos.seller_id
)
select seller_email, sum(amount) as sum from mycte
group by mycte.seller_email

{% set list1 = items.split(',') %}

{% for item in list1 %}
{{item}}
{% endfor %}